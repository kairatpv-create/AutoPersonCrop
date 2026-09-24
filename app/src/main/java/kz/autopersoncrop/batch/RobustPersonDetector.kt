package kz.autopersoncrop.batch

import android.graphics.Bitmap
import android.graphics.Matrix
import kz.autopersoncrop.core.ImageSize
import kz.autopersoncrop.core.RectD
import kz.autopersoncrop.core.WrestlingSubjectSelector
import kz.autopersoncrop.ml.PersonDetector
import kz.autopersoncrop.ml.YoloLiteRtPersonDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Accuracy-first wrapper for wrestling sequences.
 *
 * A generic person detector can return two boxes and still be wrong: one may be a referee,
 * spectator, or only a torso fragment. The old fast path stopped as soon as two boxes existed.
 * This wrapper verifies the normal result with a softer pass, fuses near-identical observations of
 * the same person, and only uses rotated/tiled recovery when the foreground action is still weak.
 * It never rotates the source photo; rotations exist only inside detector recovery and are mapped
 * back to the original coordinates.
 */
class RobustPersonDetector(
    private val delegate: YoloLiteRtPersonDetector,
) : PersonDetector {
    val accelerator: String get() = delegate.accelerator

    override fun detect(bitmap: Bitmap): List<RectD> {
        val all = ArrayList<RectD>()
        all += delegate.detect(bitmap)

        // Always verify the normal result once. This catches a second wrestler or missing limbs even
        // when the first pass already returned two unrelated/background people.
        all += delegate.detect(bitmap, VERIFY_CONFIDENCE)
        var fused = fuseDetections(all)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        // Horizontal/occluded wrestlers are often recovered when temporarily presented upright.
        all += detectRotated(bitmap, 90)
        all += detectRotated(bitmap, -90)
        fused = fuseDetections(all)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        // Final recovery: overlapping long-axis tiles make edge and small foreground people larger
        // to the fixed-size model. Keep all useful boxes; subject selection happens afterwards.
        all += detectOverlappingTiles(bitmap)
        return fuseDetections(all)
    }

    /** Explicit low-confidence calls from PhotoProcessor stay lightweight and never recurse. */
    override fun detect(bitmap: Bitmap, minConfidence: Float): List<RectD> =
        fuseDetections(delegate.detect(bitmap, minConfidence))

    private fun needsDeepRecovery(boxes: List<RectD>, bitmap: Bitmap): Boolean {
        if (boxes.isEmpty()) return true
        val image = ImageSize(bitmap.width, bitmap.height)
        val selected = runCatching { WrestlingSubjectSelector.select(image, boxes) }.getOrNull()
            ?: return true
        if (selected.isEmpty()) return true

        val u = union(selected)
        val imageArea = bitmap.width.toDouble() * bitmap.height.toDouble()
        val unionFraction = u.area / imageArea.coerceAtLeast(1.0)
        val largestFraction = selected.maxOf { it.area } / imageArea.coerceAtLeast(1.0)
        val touchesEdge = selected.any { touchesSourceEdge(it, bitmap) }

        return when {
            selected.size >= 2 -> unionFraction < 0.10 || largestFraction < 0.035
            touchesEdge -> unionFraction < 0.16
            else -> unionFraction < 0.085
        }
    }

    private fun detectRotated(src: Bitmap, degrees: Int): List<RectD> {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        return try {
            delegate.detect(rotated, ROTATED_CONFIDENCE).mapNotNull { b ->
                val mapped = when (degrees) {
                    90 -> RectD(
                        b.top,
                        src.height - b.right,
                        b.bottom,
                        src.height - b.left,
                    )
                    -90 -> RectD(
                        src.width - b.bottom,
                        b.left,
                        src.width - b.top,
                        b.right,
                    )
                    else -> return@mapNotNull null
                }
                clamp(mapped, src.width, src.height)
            }
        } finally {
            if (rotated !== src && !rotated.isRecycled) rotated.recycle()
        }
    }

    private fun detectOverlappingTiles(src: Bitmap): List<RectD> {
        val horizontal = src.width >= src.height
        val longSide = if (horizontal) src.width else src.height
        if (longSide < 420) return emptyList()

        val tileLong = (longSide * TILE_FRACTION).roundToInt().coerceIn(1, longSide)
        val end = longSide - tileLong
        val offsets = intArrayOf(0, end / 2, end).distinct()
        val found = ArrayList<RectD>()

        for (offset in offsets) {
            val tile = if (horizontal) {
                Bitmap.createBitmap(src, offset, 0, tileLong, src.height)
            } else {
                Bitmap.createBitmap(src, 0, offset, src.width, tileLong)
            }
            try {
                for (b in delegate.detect(tile, TILE_CONFIDENCE)) {
                    val mapped = if (horizontal) {
                        RectD(b.left + offset, b.top, b.right + offset, b.bottom)
                    } else {
                        RectD(b.left, b.top + offset, b.right, b.bottom + offset)
                    }
                    clamp(mapped, src.width, src.height)?.let { found += it }
                }
            } finally {
                if (tile !== src && !tile.isRecycled) tile.recycle()
            }
        }
        return found
    }

    /**
     * Fuse only boxes that are extremely likely to be repeated observations of the SAME person.
     * Instead of throwing the smaller box away, take their union so head/feet seen in one pass are
     * preserved. The threshold stays strict so two genuinely overlapping wrestlers remain separate.
     */
    private fun fuseDetections(input: List<RectD>): List<RectD> {
        if (input.isEmpty()) return emptyList()
        val fused = ArrayList<RectD>()

        for (candidate in input.sortedByDescending { it.area }) {
            val index = fused.indices
                .filter { sameObservation(fused[it], candidate) }
                .maxByOrNull { iou(fused[it], candidate) }
            if (index == null) {
                fused += candidate
            } else {
                fused[index] = union(fused[index], candidate)
            }
        }
        return fused.sortedByDescending { it.area }.take(MAX_BOXES)
    }

    private fun sameObservation(a: RectD, b: RectD): Boolean {
        val overlap = iou(a, b)
        if (overlap >= 0.92) return true
        if (overlap < 0.82) return false
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.76) return false
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.09 && dy <= 0.09
    }

    private fun touchesSourceEdge(r: RectD, src: Bitmap): Boolean {
        val ex = max(3.0, src.width * 0.012)
        val ey = max(3.0, src.height * 0.012)
        return r.left <= ex || r.right >= src.width - ex || r.top <= ey || r.bottom >= src.height - ey
    }

    private fun clamp(r: RectD, width: Int, height: Int): RectD? {
        val l = r.left.coerceIn(0.0, width.toDouble())
        val t = r.top.coerceIn(0.0, height.toDouble())
        val rr = r.right.coerceIn(l, width.toDouble())
        val bb = r.bottom.coerceIn(t, height.toDouble())
        return if (rr - l >= 2.0 && bb - t >= 2.0) RectD(l, t, rr, bb) else null
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        rects.minOf { it.left },
        rects.minOf { it.top },
        rects.maxOf { it.right },
        rects.maxOf { it.bottom },
    )

    private fun union(a: RectD, b: RectD): RectD = RectD(
        min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)
    )

    private fun iou(a: RectD, b: RectD): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val unionArea = a.area + b.area - intersection
        return if (unionArea <= 0.0) 0.0 else intersection / unionArea
    }

    override fun close() = delegate.close()

    companion object {
        private const val VERIFY_CONFIDENCE = 0.125f
        private const val ROTATED_CONFIDENCE = 0.095f
        private const val TILE_CONFIDENCE = 0.085f
        private const val TILE_FRACTION = 0.72
        private const val MAX_BOXES = 12
    }
}

/**
 * Same-package, more-specific overload intentionally wraps the service's existing detector without
 * changing BatchProcessingService. It preserves the existing lifecycle and stable GPU/CPU reporting.
 */
fun <R> YoloLiteRtPersonDetector.use(block: (RobustPersonDetector) -> R): R {
    val robust = RobustPersonDetector(this)
    return try {
        block(robust)
    } finally {
        robust.close()
    }
}
