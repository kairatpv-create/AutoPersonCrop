package kz.autopersoncrop.batch

import android.graphics.Bitmap
import android.graphics.Matrix
import kz.autopersoncrop.core.ImageSize
import kz.autopersoncrop.core.RectD
import kz.autopersoncrop.core.WrestlingSubjectSelector
import kz.autopersoncrop.ml.PersonDetector
import kz.autopersoncrop.ml.YoloLiteRtPersonDetector
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Accuracy-first detector wrapper for one/two-person wrestling and full-body action.
 *
 * The first YOLO answer is treated as a proposal, not a final truth. We verify it with a softer
 * whole-frame pass, then re-detect each selected foreground person inside an enlarged local window.
 * That local pass gives the model more pixels for head/hands/feet and is especially useful for
 * horizontal or edge poses. Weak results continue through rotated views, overlapping long-axis
 * strips, and finally a two-dimensional overlapping grid before the processor is allowed to call a
 * frame a miss. All temporary views are mapped back to the original orientation; the source image
 * itself is never rotated or modified.
 */
class RobustPersonDetector(
    private val delegate: YoloLiteRtPersonDetector,
) : PersonDetector {
    val accelerator: String get() = delegate.accelerator

    override fun detect(bitmap: Bitmap): List<RectD> {
        val initial = ArrayList<RectD>()
        initial += usefulDetections(delegate.detect(bitmap), bitmap)
        initial += usefulDetections(delegate.detect(bitmap, VERIFY_CONFIDENCE), bitmap)

        var fused = fuseDetections(initial)
        if (fused.isNotEmpty()) fused = refineForegroundSubjects(bitmap, fused)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        val rotated = ArrayList<RectD>(fused)
        rotated += detectRotated(bitmap, 90)
        rotated += detectRotated(bitmap, -90)
        fused = fuseDetections(rotated)
        if (fused.isNotEmpty()) fused = refineForegroundSubjects(bitmap, fused)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        val stripped = ArrayList<RectD>(fused)
        stripped += detectOverlappingStrips(bitmap)
        fused = fuseDetections(stripped)
        if (fused.isNotEmpty()) fused = refineForegroundSubjects(bitmap, fused)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        // Last-chance recovery for frames that used to be copied completely. Six overlapping 2D
        // windows make a small/blurred person much larger to the fixed-size model.
        val gridded = ArrayList<RectD>(fused)
        gridded += detectOverlappingGrid(bitmap)
        fused = fuseDetections(gridded)
        return if (fused.isNotEmpty()) refineForegroundSubjects(bitmap, fused) else fused
    }

    /** Explicit low-confidence calls from PhotoProcessor stay lightweight and never recurse. */
    override fun detect(bitmap: Bitmap, minConfidence: Float): List<RectD> =
        fuseDetections(usefulDetections(delegate.detect(bitmap, minConfidence), bitmap))

    /**
     * Re-open the selected main person(s) in a tighter local window and union only a matching local
     * observation with the original box. This expands missing extremities without turning a nearby
     * referee or the second wrestler into the same person.
     */
    private fun refineForegroundSubjects(src: Bitmap, boxes: List<RectD>): List<RectD> {
        if (boxes.isEmpty()) return boxes
        val image = ImageSize(src.width, src.height)
        val selected = runCatching { WrestlingSubjectSelector.select(image, boxes) }.getOrNull()
            ?.take(2)
            .orEmpty()
        if (selected.isEmpty()) return boxes

        val refined = selected.map { refineSubject(src, it) }
        val background = boxes.filterNot { box -> selected.any { sameBox(it, box) } }
        return fuseDetections(background + refined)
    }

    private fun refineSubject(src: Bitmap, anchor: RectD): RectD {
        val padX = max(anchor.width * FOCUS_PADDING_X, src.width * 0.025)
        val padY = max(anchor.height * FOCUS_PADDING_Y, src.height * 0.025)
        val region = RectD(
            anchor.left - padX,
            anchor.top - padY,
            anchor.right + padX,
            anchor.bottom + padY,
        ).let { clamp(it, src.width, src.height) } ?: return anchor

        val imageArea = src.width.toDouble() * src.height.toDouble()
        if (region.area / imageArea.coerceAtLeast(1.0) >= 0.94) return anchor

        val left = floor(region.left).toInt().coerceIn(0, src.width - 1)
        val top = floor(region.top).toInt().coerceIn(0, src.height - 1)
        val right = ceil(region.right).toInt().coerceIn(left + 1, src.width)
        val bottom = ceil(region.bottom).toInt().coerceIn(top + 1, src.height)
        val tile = Bitmap.createBitmap(src, left, top, right - left, bottom - top)

        return try {
            val candidates = delegate.detect(tile, FOCUS_CONFIDENCE)
                .mapNotNull { b ->
                    clamp(
                        RectD(b.left + left, b.top + top, b.right + left, b.bottom + top),
                        src.width,
                        src.height,
                    )
                }
                .filter { isUseful(it, src) && matchesSameForeground(anchor, it) }

            val best = candidates.maxByOrNull { focusMatchScore(anchor, it) } ?: return anchor
            union(anchor, best).let { clamp(it, src.width, src.height) } ?: anchor
        } finally {
            if (tile !== src && !tile.isRecycled) tile.recycle()
        }
    }

    private fun matchesSameForeground(anchor: RectD, candidate: RectD): Boolean {
        val overlap = overlapFractionOfSmaller(anchor, candidate)
        val areaRatio = min(anchor.area, candidate.area) / max(anchor.area, candidate.area).coerceAtLeast(1.0)
        val dx = abs(anchor.centerX - candidate.centerX) / max(anchor.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(anchor.centerY - candidate.centerY) / max(anchor.height, candidate.height).coerceAtLeast(1.0)

        return when {
            iou(anchor, candidate) >= 0.72 && dx <= 0.16 && dy <= 0.16 -> true
            overlap >= 0.90 && areaRatio >= 0.48 && dx <= 0.13 && dy <= 0.13 -> true
            else -> false
        }
    }

    private fun focusMatchScore(anchor: RectD, candidate: RectD): Double {
        val overlap = overlapFractionOfSmaller(anchor, candidate)
        val areaGain = (candidate.area / anchor.area.coerceAtLeast(1.0)).coerceIn(0.5, 1.8)
        val dx = abs(anchor.centerX - candidate.centerX) / max(anchor.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(anchor.centerY - candidate.centerY) / max(anchor.height, candidate.height).coerceAtLeast(1.0)
        return overlap * 1.6 + areaGain * 0.25 - (dx + dy) * 0.55
    }

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
        val shape = max(u.width / u.height.coerceAtLeast(1.0), u.height / u.width.coerceAtLeast(1.0))

        return when {
            selected.size >= 2 -> unionFraction < 0.105 || largestFraction < 0.032
            touchesEdge -> true
            shape > 3.8 -> true
            else -> unionFraction < 0.20
        }
    }

    private fun detectRotated(src: Bitmap, degrees: Int): List<RectD> {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        return try {
            val mapped = delegate.detect(rotated, ROTATED_CONFIDENCE).mapNotNull { b ->
                val r = when (degrees) {
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
                clamp(r, src.width, src.height)
            }
            usefulDetections(mapped, src)
        } finally {
            if (rotated !== src && !rotated.isRecycled) rotated.recycle()
        }
    }

    private fun detectOverlappingStrips(src: Bitmap): List<RectD> {
        val horizontal = src.width >= src.height
        val longSide = if (horizontal) src.width else src.height
        if (longSide < 420) return emptyList()

        val tileLong = (longSide * STRIP_FRACTION).roundToInt().coerceIn(1, longSide)
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
                for (b in delegate.detect(tile, STRIP_CONFIDENCE)) {
                    val mapped = if (horizontal) {
                        RectD(b.left + offset, b.top, b.right + offset, b.bottom)
                    } else {
                        RectD(b.left, b.top + offset, b.right, b.bottom + offset)
                    }
                    clamp(mapped, src.width, src.height)?.let { if (isUseful(it, src)) found += it }
                }
            } finally {
                if (tile !== src && !tile.isRecycled) tile.recycle()
            }
        }
        return found
    }

    private fun detectOverlappingGrid(src: Bitmap): List<RectD> {
        if (src.width < 360 || src.height < 300) return emptyList()

        val landscape = src.width >= src.height
        val tileW = (src.width * if (landscape) 0.68 else 0.86).roundToInt().coerceIn(1, src.width)
        val tileH = (src.height * if (landscape) 0.86 else 0.68).roundToInt().coerceIn(1, src.height)
        val xEnd = src.width - tileW
        val yEnd = src.height - tileH
        val xOffsets = if (landscape) intArrayOf(0, xEnd / 2, xEnd).distinct() else intArrayOf(0, xEnd).distinct()
        val yOffsets = if (landscape) intArrayOf(0, yEnd).distinct() else intArrayOf(0, yEnd / 2, yEnd).distinct()
        val found = ArrayList<RectD>()

        for (top in yOffsets) {
            for (left in xOffsets) {
                val tile = Bitmap.createBitmap(src, left, top, tileW, tileH)
                try {
                    for (b in delegate.detect(tile, GRID_CONFIDENCE)) {
                        val mapped = RectD(
                            b.left + left,
                            b.top + top,
                            b.right + left,
                            b.bottom + top,
                        )
                        clamp(mapped, src.width, src.height)?.let { if (isUseful(it, src)) found += it }
                    }
                } finally {
                    if (tile !== src && !tile.isRecycled) tile.recycle()
                }
            }
        }
        return found
    }

    private fun usefulDetections(input: List<RectD>, src: Bitmap): List<RectD> =
        input.filter { isUseful(it, src) }

    private fun isUseful(box: RectD, src: Bitmap): Boolean {
        if (box.width < 5.0 || box.height < 5.0) return false
        val imageArea = src.width.toDouble() * src.height.toDouble()
        return box.area / imageArea.coerceAtLeast(1.0) >= MIN_BOX_AREA_FRACTION
    }

    /** Preserve overlapping wrestlers; merge only extremely similar observations of one person. */
    private fun fuseDetections(input: List<RectD>): List<RectD> {
        if (input.isEmpty()) return emptyList()
        val fused = ArrayList<RectD>()

        for (candidate in input.sortedByDescending { it.area }) {
            val index = fused.indices
                .filter { sameObservation(fused[it], candidate) }
                .maxByOrNull { iou(fused[it], candidate) }
            if (index == null) fused += candidate
            else fused[index] = union(fused[index], candidate)
        }
        return fused.sortedByDescending { it.area }.take(MAX_BOXES)
    }

    private fun sameObservation(a: RectD, b: RectD): Boolean {
        val overlap = iou(a, b)
        if (overlap >= 0.93) return true
        if (overlap < 0.84) return false
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.78) return false
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.075 && dy <= 0.075
    }

    private fun sameBox(a: RectD, b: RectD): Boolean {
        val ratio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        return iou(a, b) >= 0.985 && ratio >= 0.97
    }

    private fun touchesSourceEdge(r: RectD, src: Bitmap): Boolean {
        val ex = max(2.0, src.width * 0.006)
        val ey = max(2.0, src.height * 0.006)
        return r.left <= ex || r.right >= src.width - ex || r.top <= ey || r.bottom >= src.height - ey
    }

    private fun overlapFractionOfSmaller(a: RectD, b: RectD): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        return intersection / min(a.area, b.area).coerceAtLeast(1.0)
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
        private const val VERIFY_CONFIDENCE = 0.115f
        private const val FOCUS_CONFIDENCE = 0.095f
        private const val ROTATED_CONFIDENCE = 0.090f
        private const val STRIP_CONFIDENCE = 0.080f
        private const val GRID_CONFIDENCE = 0.070f

        private const val FOCUS_PADDING_X = 0.38
        private const val FOCUS_PADDING_Y = 0.30
        private const val STRIP_FRACTION = 0.72
        private const val MIN_BOX_AREA_FRACTION = 0.0015
        private const val MAX_BOXES = 16
    }
}

/**
 * Same-package, more-specific overload keeps BatchProcessingService unchanged and preserves the
 * standard inline-use lifecycle/non-local-return behaviour.
 */
inline fun <R> YoloLiteRtPersonDetector.use(block: (RobustPersonDetector) -> R): R {
    val robust = RobustPersonDetector(this)
    return try {
        block(robust)
    } finally {
        robust.close()
    }
}
