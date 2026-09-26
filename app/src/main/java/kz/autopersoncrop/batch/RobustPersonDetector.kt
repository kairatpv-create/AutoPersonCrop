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

/**
 * Accuracy-first detector for the controlled input rule: every source frame contains one person or
 * two wrestlers, and no other people. Because false human candidates are now much less dangerous,
 * recovery thresholds can be softer and a one-person result is always checked more deeply for a
 * possible second wrestler before we accept it.
 */
class RobustPersonDetector(
    private val delegate: YoloLiteRtPersonDetector,
) : PersonDetector {
    val accelerator: String get() = delegate.accelerator

    override fun detect(bitmap: Bitmap): List<RectD> {
        val all = ArrayList<RectD>()
        all += usefulDetections(delegate.detect(bitmap), bitmap)
        all += usefulDetections(delegate.detect(bitmap, VERIFY_CONFIDENCE), bitmap)

        var fused = fuseDetections(all)
        if (fused.isNotEmpty()) fused = refineForegroundSubjects(bitmap, fused)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        all += detectRotated(bitmap, 90)
        all += detectRotated(bitmap, -90)
        fused = fuseDetections(all)
        if (fused.isNotEmpty()) fused = refineForegroundSubjects(bitmap, fused)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        all += detectOverlappingStrips(bitmap)
        fused = fuseDetections(all)
        if (fused.isNotEmpty()) fused = refineForegroundSubjects(bitmap, fused)
        if (!needsDeepRecovery(fused, bitmap)) return fused

        // Last-chance 2D scan. This is intentionally permissive now that no unrelated people are in
        // the source material. It is preferable to inspect more candidate boxes than to copy a full
        // unprocessed photo because one wrestler was missed in the whole-frame pass.
        all += detectOverlappingGrid(bitmap)
        fused = fuseDetections(all)
        return if (fused.isNotEmpty()) refineForegroundSubjects(bitmap, fused) else fused
    }

    override fun detect(bitmap: Bitmap, minConfidence: Float): List<RectD> =
        fuseDetections(usefulDetections(delegate.detect(bitmap, minOf(minConfidence, SOFT_CALL_CAP)), bitmap))

    private fun refineForegroundSubjects(src: Bitmap, boxes: List<RectD>): List<RectD> {
        if (boxes.isEmpty()) return boxes
        val image = ImageSize(src.width, src.height)
        val selected = runCatching { WrestlingSubjectSelector.select(image, boxes) }.getOrNull()
            ?.take(2)
            .orEmpty()
        if (selected.isEmpty()) return boxes

        val refined = selected.map { refineSubject(src, it) }
        val remainder = boxes.filterNot { box -> selected.any { sameBox(it, box) } }
        return fuseDetections(remainder + refined)
    }

    private fun refineSubject(src: Bitmap, anchor: RectD): RectD {
        val padX = max(anchor.width * FOCUS_PADDING_X, src.width * 0.030)
        val padY = max(anchor.height * FOCUS_PADDING_Y, src.height * 0.030)
        val region = RectD(
            anchor.left - padX,
            anchor.top - padY,
            anchor.right + padX,
            anchor.bottom + padY,
        ).let { clamp(it, src.width, src.height) } ?: return anchor

        val imageArea = src.width.toDouble() * src.height.toDouble()
        if (region.area / imageArea.coerceAtLeast(1.0) >= 0.96) return anchor

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
            iou(anchor, candidate) >= 0.68 && dx <= 0.18 && dy <= 0.18 -> true
            overlap >= 0.88 && areaRatio >= 0.42 && dx <= 0.16 && dy <= 0.16 -> true
            else -> false
        }
    }

    private fun focusMatchScore(anchor: RectD, candidate: RectD): Double {
        val overlap = overlapFractionOfSmaller(anchor, candidate)
        val areaGain = (candidate.area / anchor.area.coerceAtLeast(1.0)).coerceIn(0.5, 2.0)
        val dx = abs(anchor.centerX - candidate.centerX) / max(anchor.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(anchor.centerY - candidate.centerY) / max(anchor.height, candidate.height).coerceAtLeast(1.0)
        return overlap * 1.7 + areaGain * 0.28 - (dx + dy) * 0.45
    }

    private fun needsDeepRecovery(boxes: List<RectD>, bitmap: Bitmap): Boolean {
        if (boxes.isEmpty()) return true
        val image = ImageSize(bitmap.width, bitmap.height)
        val selected = runCatching { WrestlingSubjectSelector.select(image, boxes) }.getOrNull()
            ?: return true
        if (selected.isEmpty()) return true

        // With controlled source material, a single detected person may still mean the second wrestler
        // was missed. Therefore one-person results always receive the full recovery stack.
        if (selected.size == 1) return true

        val u = union(selected)
        val imageArea = bitmap.width.toDouble() * bitmap.height.toDouble()
        val unionFraction = u.area / imageArea.coerceAtLeast(1.0)
        val largestFraction = selected.maxOf { it.area } / imageArea.coerceAtLeast(1.0)
        return unionFraction < 0.080 || largestFraction < 0.020
    }

    private fun detectRotated(src: Bitmap, degrees: Int): List<RectD> {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        return try {
            val mapped = delegate.detect(rotated, ROTATED_CONFIDENCE).mapNotNull { b ->
                val r = when (degrees) {
                    90 -> RectD(b.top, src.height - b.right, b.bottom, src.height - b.left)
                    -90 -> RectD(src.width - b.bottom, b.left, src.width - b.top, b.right)
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
            val tile = if (horizontal) Bitmap.createBitmap(src, offset, 0, tileLong, src.height)
            else Bitmap.createBitmap(src, 0, offset, src.width, tileLong)
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
        if (src.width < 320 || src.height < 260) return emptyList()

        val landscape = src.width >= src.height
        val tileW = (src.width * if (landscape) 0.64 else 0.84).roundToInt().coerceIn(1, src.width)
        val tileH = (src.height * if (landscape) 0.84 else 0.64).roundToInt().coerceIn(1, src.height)
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
        if (box.width < 4.0 || box.height < 4.0) return false
        val imageArea = src.width.toDouble() * src.height.toDouble()
        return box.area / imageArea.coerceAtLeast(1.0) >= MIN_BOX_AREA_FRACTION
    }

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
        if (areaRatio < 0.76) return false
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.085 && dy <= 0.085
    }

    private fun sameBox(a: RectD, b: RectD): Boolean {
        val ratio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        return iou(a, b) >= 0.985 && ratio >= 0.97
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
        rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom }
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
        private const val VERIFY_CONFIDENCE = 0.095f
        private const val FOCUS_CONFIDENCE = 0.070f
        private const val ROTATED_CONFIDENCE = 0.070f
        private const val STRIP_CONFIDENCE = 0.060f
        private const val GRID_CONFIDENCE = 0.050f
        private const val SOFT_CALL_CAP = 0.070f

        private const val FOCUS_PADDING_X = 0.42
        private const val FOCUS_PADDING_Y = 0.34
        private const val STRIP_FRACTION = 0.70
        private const val MIN_BOX_AREA_FRACTION = 0.0008
        private const val MAX_BOXES = 18
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
