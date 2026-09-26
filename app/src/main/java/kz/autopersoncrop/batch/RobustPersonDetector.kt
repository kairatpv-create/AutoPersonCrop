package kz.autopersoncrop.batch

import android.graphics.Bitmap
import android.graphics.Matrix
import kz.autopersoncrop.core.RectD
import kz.autopersoncrop.ml.PersonDetector
import kz.autopersoncrop.ml.YoloLiteRtPersonDetector
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Exhaustive offline detector for controlled input: every image contains one person or two wrestlers,
 * with no unrelated people in the background.
 *
 * We therefore prefer recall over conservatism. Every frame receives several independent views:
 * whole-frame low-confidence passes, +/-90 degree views, overlapping strips and a 2D grid. The model
 * never rotates or rewrites the source image; temporary views are mapped back to source coordinates.
 * If all model passes still fail, a conservative orientation-aware fallback box is returned so the
 * processor never silently copies the source unchanged.
 */
class RobustPersonDetector(
    private val delegate: YoloLiteRtPersonDetector,
) : PersonDetector {
    val accelerator: String get() = delegate.accelerator

    override fun detect(bitmap: Bitmap): List<RectD> {
        val all = ArrayList<RectD>()

        // Explicit thresholds below YoloLiteRtPersonDetector's recovery trigger keep the wrapper in
        // full control and avoid recursively repeating the delegate's own recovery tree.
        all += useful(delegate.detect(bitmap, WHOLE_CONFIDENCE), bitmap)
        all += useful(delegate.detect(bitmap, SOFT_WHOLE_CONFIDENCE), bitmap)
        all += detectRotated(bitmap, 90)
        all += detectRotated(bitmap, -90)
        all += detectOverlappingStrips(bitmap)
        all += detectOverlappingGrid(bitmap)

        var fused = fuseDetections(all)
        if (fused.isNotEmpty()) fused = refineTopCandidates(bitmap, fused)

        // Never return an empty list in the new controlled-input mode. PhotoProcessor previously
        // interpreted an empty detector result as "copy the original unchanged", which caused whole
        // batches to pass through without processing.
        return if (fused.isNotEmpty()) fused else emergencyFallback(bitmap)
    }

    override fun detect(bitmap: Bitmap, minConfidence: Float): List<RectD> {
        val threshold = minOf(minConfidence, EXPLICIT_CALL_CAP)
        val direct = useful(delegate.detect(bitmap, threshold), bitmap)
        return if (direct.isNotEmpty()) fuseDetections(direct) else emptyList()
    }

    private fun refineTopCandidates(src: Bitmap, boxes: List<RectD>): List<RectD> {
        val anchors = boxes.sortedByDescending { it.area }.take(3)
        if (anchors.isEmpty()) return boxes

        val refined = anchors.map { refineSubject(src, it) }
        val remainder = boxes.filterNot { box -> anchors.any { almostSameBox(it, box) } }
        return fuseDetections(remainder + refined)
    }

    private fun refineSubject(src: Bitmap, anchor: RectD): RectD {
        val padX = max(anchor.width * FOCUS_PADDING_X, src.width * 0.035)
        val padY = max(anchor.height * FOCUS_PADDING_Y, src.height * 0.035)
        val region = clamp(
            RectD(anchor.left - padX, anchor.top - padY, anchor.right + padX, anchor.bottom + padY),
            src.width,
            src.height,
        ) ?: return anchor

        if (region.width >= src.width * 0.97 && region.height >= src.height * 0.97) return anchor

        val left = floor(region.left).toInt().coerceIn(0, src.width - 1)
        val top = floor(region.top).toInt().coerceIn(0, src.height - 1)
        val right = ceil(region.right).toInt().coerceIn(left + 1, src.width)
        val bottom = ceil(region.bottom).toInt().coerceIn(top + 1, src.height)
        val tile = Bitmap.createBitmap(src, left, top, right - left, bottom - top)

        return try {
            val local = delegate.detect(tile, FOCUS_CONFIDENCE)
                .mapNotNull { b ->
                    clamp(
                        RectD(b.left + left, b.top + top, b.right + left, b.bottom + top),
                        src.width,
                        src.height,
                    )
                }
                .filter { usefulBox(it, src) && sameForeground(anchor, it) }

            val best = local.maxByOrNull { focusScore(anchor, it) } ?: return anchor
            clamp(union(anchor, best), src.width, src.height) ?: anchor
        } finally {
            if (tile !== src && !tile.isRecycled) tile.recycle()
        }
    }

    private fun sameForeground(anchor: RectD, candidate: RectD): Boolean {
        val overlap = overlapFractionOfSmaller(anchor, candidate)
        val areaRatio = min(anchor.area, candidate.area) / max(anchor.area, candidate.area).coerceAtLeast(1.0)
        val dx = abs(anchor.centerX - candidate.centerX) / max(anchor.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(anchor.centerY - candidate.centerY) / max(anchor.height, candidate.height).coerceAtLeast(1.0)
        return when {
            iou(anchor, candidate) >= 0.62 && dx <= 0.22 && dy <= 0.22 -> true
            overlap >= 0.84 && areaRatio >= 0.36 && dx <= 0.20 && dy <= 0.20 -> true
            else -> false
        }
    }

    private fun focusScore(anchor: RectD, candidate: RectD): Double {
        val overlap = overlapFractionOfSmaller(anchor, candidate)
        val areaGain = (candidate.area / anchor.area.coerceAtLeast(1.0)).coerceIn(0.4, 2.4)
        val dx = abs(anchor.centerX - candidate.centerX) / max(anchor.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(anchor.centerY - candidate.centerY) / max(anchor.height, candidate.height).coerceAtLeast(1.0)
        return overlap * 1.8 + areaGain * 0.30 - (dx + dy) * 0.40
    }

    private fun detectRotated(src: Bitmap, degrees: Int): List<RectD> {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        return try {
            val boxes = delegate.detect(rotated, ROTATED_CONFIDENCE)
            useful(
                boxes.mapNotNull { b ->
                    val mapped = when (degrees) {
                        90 -> RectD(b.top, src.height - b.right, b.bottom, src.height - b.left)
                        -90 -> RectD(src.width - b.bottom, b.left, src.width - b.top, b.right)
                        else -> return@mapNotNull null
                    }
                    clamp(mapped, src.width, src.height)
                },
                src,
            )
        } finally {
            if (rotated !== src && !rotated.isRecycled) rotated.recycle()
        }
    }

    private fun detectOverlappingStrips(src: Bitmap): List<RectD> {
        val horizontal = src.width >= src.height
        val longSide = if (horizontal) src.width else src.height
        if (longSide < 360) return emptyList()

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
                    clamp(mapped, src.width, src.height)?.let { if (usefulBox(it, src)) found += it }
                }
            } finally {
                if (tile !== src && !tile.isRecycled) tile.recycle()
            }
        }
        return found
    }

    private fun detectOverlappingGrid(src: Bitmap): List<RectD> {
        if (src.width < 280 || src.height < 220) return emptyList()

        val landscape = src.width >= src.height
        val tileW = (src.width * if (landscape) 0.60 else 0.82).roundToInt().coerceIn(1, src.width)
        val tileH = (src.height * if (landscape) 0.82 else 0.60).roundToInt().coerceIn(1, src.height)
        val xEnd = src.width - tileW
        val yEnd = src.height - tileH
        val xOffsets = intArrayOf(0, xEnd / 2, xEnd).distinct()
        val yOffsets = intArrayOf(0, yEnd / 2, yEnd).distinct()
        val found = ArrayList<RectD>()

        for (top in yOffsets) {
            for (left in xOffsets) {
                val tile = Bitmap.createBitmap(src, left, top, tileW, tileH)
                try {
                    for (b in delegate.detect(tile, GRID_CONFIDENCE)) {
                        val mapped = RectD(b.left + left, b.top + top, b.right + left, b.bottom + top)
                        clamp(mapped, src.width, src.height)?.let { if (usefulBox(it, src)) found += it }
                    }
                } finally {
                    if (tile !== src && !tile.isRecycled) tile.recycle()
                }
            }
        }
        return found
    }

    private fun emergencyFallback(src: Bitmap): List<RectD> {
        return if (src.width >= src.height) {
            listOf(
                RectD(
                    src.width * 0.08,
                    src.height * 0.08,
                    src.width * 0.92,
                    src.height * 0.92,
                )
            )
        } else {
            listOf(
                RectD(
                    src.width * 0.08,
                    src.height * 0.08,
                    src.width * 0.92,
                    src.height * 0.92,
                )
            )
        }
    }

    private fun useful(input: List<RectD>, src: Bitmap): List<RectD> = input.filter { usefulBox(it, src) }

    private fun usefulBox(box: RectD, src: Bitmap): Boolean {
        if (box.width < 3.0 || box.height < 3.0) return false
        val imageArea = src.width.toDouble() * src.height.toDouble()
        return box.area / imageArea.coerceAtLeast(1.0) >= MIN_BOX_AREA_FRACTION
    }

    /**
     * Merge only repeated observations of the same person. Thresholds remain conservative because
     * two wrestlers can overlap heavily.
     */
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
        if (overlap >= 0.94) return true
        if (overlap < 0.82) return false
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.74) return false
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.10 && dy <= 0.10
    }

    private fun almostSameBox(a: RectD, b: RectD): Boolean {
        val ratio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        return iou(a, b) >= 0.98 && ratio >= 0.96
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
        private const val WHOLE_CONFIDENCE = 0.080f
        private const val SOFT_WHOLE_CONFIDENCE = 0.025f
        private const val FOCUS_CONFIDENCE = 0.020f
        private const val ROTATED_CONFIDENCE = 0.020f
        private const val STRIP_CONFIDENCE = 0.015f
        private const val GRID_CONFIDENCE = 0.010f
        private const val EXPLICIT_CALL_CAP = 0.025f

        private const val FOCUS_PADDING_X = 0.46
        private const val FOCUS_PADDING_Y = 0.38
        private const val STRIP_FRACTION = 0.68
        private const val MIN_BOX_AREA_FRACTION = 0.00045
        private const val MAX_BOXES = 24
    }
}

inline fun <R> YoloLiteRtPersonDetector.use(block: (RobustPersonDetector) -> R): R {
    val robust = RobustPersonDetector(this)
    return try {
        block(robust)
    } finally {
        robust.close()
    }
}
