package kz.autopersoncrop.batch

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kz.autopersoncrop.core.RectD
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
 * Detector for controlled material: every source contains exactly one person or two wrestlers and no
 * unrelated people. The important rule here is consensus, not an extremely low confidence threshold.
 *
 * Several independent views are evaluated. A candidate becomes a real subject only when it is
 * confirmed by more than one view, or by the stronger whole-frame pass with plausible geometry.
 * This prevents a single huge low-confidence YOLO box from turning the crop back into the full image.
 */
class RobustPersonDetector(
    private val delegate: YoloLiteRtPersonDetector,
) : PersonDetector {
    val accelerator: String get() = delegate.accelerator

    private data class Observation(
        val rect: RectD,
        val passId: Int,
        val weight: Double,
    )

    private data class CandidateCluster(
        val observations: MutableList<Observation> = ArrayList(),
    )

    private data class RankedCandidate(
        val score: Double,
        val cluster: CandidateCluster,
        val passCount: Int,
        val hasStrongWhole: Boolean,
    )

    override fun detect(bitmap: Bitmap): List<RectD> {
        val observations = ArrayList<Observation>()
        observations += observe(delegate.detect(bitmap, WHOLE_STRONG_CONFIDENCE), bitmap, PASS_WHOLE_STRONG, 4.0)
        observations += observe(delegate.detect(bitmap, WHOLE_SOFT_CONFIDENCE), bitmap, PASS_WHOLE_SOFT, 2.8)
        observations += detectRotated(bitmap, 90, PASS_ROTATED_90)
        observations += detectRotated(bitmap, -90, PASS_ROTATED_MINUS_90)
        observations += detectOverlappingStrips(bitmap)
        observations += detectOverlappingGrid(bitmap)

        val clusters = buildClusters(observations)
        val ranked = clusters
            .mapNotNull { cluster -> rankCluster(cluster, bitmap) }
            .sortedByDescending { it.score }

        val selected = selectSubjects(ranked, bitmap)
        if (selected.isEmpty()) {
            Log.w(TAG, "No reliable person consensus: observations=${observations.size}, clusters=${clusters.size}")
            return failureMarker()
        }

        val refined = selected.map { refineSubject(bitmap, it) }
        Log.i(
            TAG,
            "Person consensus: observations=${observations.size}, clusters=${clusters.size}, selected=${refined.size}, boxes=${refined.joinToString()}",
        )
        return refined
    }

    /** PhotoProcessor should normally stop after detect(bitmap). Keep explicit calls conservative. */
    override fun detect(bitmap: Bitmap, minConfidence: Float): List<RectD> {
        val threshold = maxOf(minConfidence, EXPLICIT_MIN_CONFIDENCE)
        return delegate.detect(bitmap, threshold)
            .filter { usefulBox(it, bitmap) }
            .sortedByDescending { it.area }
            .take(4)
    }

    private fun observe(
        boxes: List<RectD>,
        src: Bitmap,
        passId: Int,
        weight: Double,
    ): List<Observation> = boxes
        .filter { usefulBox(it, src) }
        .map { Observation(it, passId, weight) }

    private fun detectRotated(src: Bitmap, degrees: Int, passId: Int): List<Observation> {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        return try {
            delegate.detect(rotated, ROTATED_CONFIDENCE)
                .mapNotNull { box ->
                    val mapped = when (degrees) {
                        90 -> RectD(
                            box.top,
                            src.height - box.right,
                            box.bottom,
                            src.height - box.left,
                        )
                        -90 -> RectD(
                            src.width - box.bottom,
                            box.left,
                            src.width - box.top,
                            box.right,
                        )
                        else -> return@mapNotNull null
                    }
                    clamp(mapped, src.width, src.height)
                }
                .filter { usefulBox(it, src) }
                .map { Observation(it, passId, 2.5) }
        } finally {
            if (rotated !== src && !rotated.isRecycled) rotated.recycle()
        }
    }

    private fun detectOverlappingStrips(src: Bitmap): List<Observation> {
        val horizontal = src.width >= src.height
        val longSide = if (horizontal) src.width else src.height
        if (longSide < 360) return emptyList()

        val tileLong = (longSide * STRIP_FRACTION).roundToInt().coerceIn(1, longSide)
        val end = longSide - tileLong
        val offsets = intArrayOf(0, end / 2, end).distinct()
        val found = ArrayList<Observation>()

        offsets.forEachIndexed { index, offset ->
            val tile = if (horizontal) {
                Bitmap.createBitmap(src, offset, 0, tileLong, src.height)
            } else {
                Bitmap.createBitmap(src, 0, offset, src.width, tileLong)
            }
            try {
                delegate.detect(tile, STRIP_CONFIDENCE).forEach { box ->
                    val mapped = if (horizontal) {
                        RectD(box.left + offset, box.top, box.right + offset, box.bottom)
                    } else {
                        RectD(box.left, box.top + offset, box.right, box.bottom + offset)
                    }
                    clamp(mapped, src.width, src.height)
                        ?.takeIf { usefulBox(it, src) }
                        ?.let { found += Observation(it, PASS_STRIP_BASE + index, 1.9) }
                }
            } finally {
                if (tile !== src && !tile.isRecycled) tile.recycle()
            }
        }
        return found
    }

    private fun detectOverlappingGrid(src: Bitmap): List<Observation> {
        if (src.width < 280 || src.height < 220) return emptyList()

        val landscape = src.width >= src.height
        val tileW = (src.width * if (landscape) 0.62 else 0.84).roundToInt().coerceIn(1, src.width)
        val tileH = (src.height * if (landscape) 0.84 else 0.62).roundToInt().coerceIn(1, src.height)
        val xEnd = src.width - tileW
        val yEnd = src.height - tileH
        val xOffsets = intArrayOf(0, xEnd / 2, xEnd).distinct()
        val yOffsets = intArrayOf(0, yEnd / 2, yEnd).distinct()
        val found = ArrayList<Observation>()
        var pass = PASS_GRID_BASE

        for (top in yOffsets) {
            for (left in xOffsets) {
                val tile = Bitmap.createBitmap(src, left, top, tileW, tileH)
                try {
                    delegate.detect(tile, GRID_CONFIDENCE).forEach { box ->
                        val mapped = RectD(
                            box.left + left,
                            box.top + top,
                            box.right + left,
                            box.bottom + top,
                        )
                        clamp(mapped, src.width, src.height)
                            ?.takeIf { usefulBox(it, src) }
                            ?.let { found += Observation(it, pass, 1.45) }
                    }
                } finally {
                    if (tile !== src && !tile.isRecycled) tile.recycle()
                }
                pass++
            }
        }
        return found
    }

    private fun buildClusters(observations: List<Observation>): List<CandidateCluster> {
        val clusters = ArrayList<CandidateCluster>()
        for (observation in observations.sortedByDescending { it.weight }) {
            val best = clusters
                .map { it to clusterAffinity(representative(it), observation.rect) }
                .filter { it.second >= CLUSTER_AFFINITY_THRESHOLD }
                .maxByOrNull { it.second }
                ?.first

            if (best == null) {
                clusters += CandidateCluster(mutableListOf(observation))
            } else {
                best.observations += observation
            }
        }
        return clusters
    }

    private fun rankCluster(cluster: CandidateCluster, src: Bitmap): RankedCandidate? {
        if (cluster.observations.isEmpty()) return null
        val rect = representative(cluster)
        if (!usefulBox(rect, src)) return null

        val passWeights = cluster.observations
            .groupBy { it.passId }
            .mapValues { (_, values) -> values.maxOf { it.weight } }
        val passCount = passWeights.size
        val support = passWeights.values.sum()
        val hasStrongWhole = PASS_WHOLE_STRONG in passWeights

        val widthFraction = rect.width / src.width.toDouble()
        val heightFraction = rect.height / src.height.toDouble()
        val areaFraction = rect.area / (src.width.toDouble() * src.height.toDouble())

        if (widthFraction > 0.92 && heightFraction > 0.92) return null
        if (widthFraction > 0.86 && heightFraction > 0.86 && passCount < 3) return null

        val framePenalty = if (widthFraction > 0.84 && heightFraction > 0.84) 3.0 else 0.0
        val sizeBonus = sqrt(areaFraction.coerceIn(0.0, 0.55)) * 2.2
        val score = support + passCount * 0.65 + sizeBonus - framePenalty
        if (score <= 0.5) return null

        return RankedCandidate(score, cluster, passCount, hasStrongWhole)
    }

    private fun selectSubjects(
        ranked: List<RankedCandidate>,
        src: Bitmap,
    ): List<RectD> {
        if (ranked.isEmpty()) return emptyList()

        val primaryRanked = ranked.firstOrNull { it.hasStrongWhole || it.passCount >= 2 } ?: return emptyList()
        val primary = representative(primaryRanked.cluster)
        val selected = ArrayList<RectD>()
        selected += primary

        for (candidateRanked in ranked) {
            if (candidateRanked === primaryRanked) continue
            val candidate = representative(candidateRanked.cluster)
            if (sameFinalSubject(primary, candidate)) continue
            if (!plausiblePartner(primary, candidate, src)) continue

            val minimumSecondScore = if (candidateRanked.passCount >= 2 || candidateRanked.hasStrongWhole) {
                primaryRanked.score * SECOND_SCORE_RATIO
            } else {
                primaryRanked.score * SINGLE_VIEW_SECOND_SCORE_RATIO
            }
            if (candidateRanked.score < minimumSecondScore) continue
            selected += candidate
            break
        }
        return selected
    }

    private fun plausiblePartner(a: RectD, b: RectD, src: Bitmap): Boolean {
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < MIN_SECOND_AREA_RATIO) return false
        if (overlapFractionOfSmaller(a, b) > 0.02) return true

        val horizontalGap = when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0.0
        }
        val verticalGap = when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0.0
        }
        val normalizedGap = sqrt(
            (horizontalGap / src.width.toDouble()).let { it * it } +
                (verticalGap / src.height.toDouble()).let { it * it }
        )
        return normalizedGap <= MAX_PARTNER_GAP
    }

    private fun refineSubject(src: Bitmap, anchor: RectD): RectD {
        val padX = max(anchor.width * 0.30, src.width * 0.025)
        val padY = max(anchor.height * 0.24, src.height * 0.025)
        val region = clamp(
            RectD(anchor.left - padX, anchor.top - padY, anchor.right + padX, anchor.bottom + padY),
            src.width,
            src.height,
        ) ?: return anchor

        if (region.width >= src.width * 0.96 && region.height >= src.height * 0.96) return anchor

        val left = floor(region.left).toInt().coerceIn(0, src.width - 1)
        val top = floor(region.top).toInt().coerceIn(0, src.height - 1)
        val right = ceil(region.right).toInt().coerceIn(left + 1, src.width)
        val bottom = ceil(region.bottom).toInt().coerceIn(top + 1, src.height)
        val tile = Bitmap.createBitmap(src, left, top, right - left, bottom - top)

        return try {
            val local = delegate.detect(tile, FOCUS_CONFIDENCE)
                .mapNotNull { box ->
                    clamp(
                        RectD(box.left + left, box.top + top, box.right + left, box.bottom + top),
                        src.width,
                        src.height,
                    )
                }
                .filter { usefulBox(it, src) && sameForeground(anchor, it) }

            val best = local.maxByOrNull { localMatchScore(anchor, it) } ?: return addSafety(anchor, src)
            val merged = union(anchor, best)
            val maxAllowedArea = anchor.area * MAX_REFINEMENT_AREA_GROWTH
            val safe = if (merged.area <= maxAllowedArea) merged else anchor
            addSafety(safe, src)
        } finally {
            if (tile !== src && !tile.isRecycled) tile.recycle()
        }
    }

    private fun addSafety(rect: RectD, src: Bitmap): RectD {
        val mx = max(rect.width * DETECTOR_SAFETY, src.width * 0.004)
        val my = max(rect.height * DETECTOR_SAFETY, src.height * 0.004)
        return clamp(
            RectD(rect.left - mx, rect.top - my, rect.right + mx, rect.bottom + my),
            src.width,
            src.height,
        ) ?: rect
    }

    private fun sameForeground(anchor: RectD, candidate: RectD): Boolean {
        val overlap = overlapFractionOfSmaller(anchor, candidate)
        val areaRatio = min(anchor.area, candidate.area) / max(anchor.area, candidate.area).coerceAtLeast(1.0)
        val dx = abs(anchor.centerX - candidate.centerX) / max(anchor.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(anchor.centerY - candidate.centerY) / max(anchor.height, candidate.height).coerceAtLeast(1.0)
        return when {
            iou(anchor, candidate) >= 0.58 && dx <= 0.24 && dy <= 0.24 -> true
            overlap >= 0.82 && areaRatio >= 0.32 && dx <= 0.22 && dy <= 0.22 -> true
            else -> false
        }
    }

    private fun localMatchScore(anchor: RectD, candidate: RectD): Double {
        val overlap = overlapFractionOfSmaller(anchor, candidate)
        val areaGain = (candidate.area / anchor.area.coerceAtLeast(1.0)).coerceIn(0.4, 2.0)
        val dx = abs(anchor.centerX - candidate.centerX) / max(anchor.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(anchor.centerY - candidate.centerY) / max(anchor.height, candidate.height).coerceAtLeast(1.0)
        return overlap * 1.8 + areaGain * 0.24 - (dx + dy) * 0.45
    }

    private fun representative(cluster: CandidateCluster): RectD {
        val observations = cluster.observations
        return RectD(
            median(observations.map { it.rect.left }),
            median(observations.map { it.rect.top }),
            median(observations.map { it.rect.right }),
            median(observations.map { it.rect.bottom }),
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun clusterAffinity(a: RectD, b: RectD): Double {
        val overlap = iou(a, b)
        val smallerOverlap = overlapFractionOfSmaller(a, b)
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)

        if (areaRatio < 0.30 || dx > 0.30 || dy > 0.30) return 0.0
        return overlap * 0.60 + smallerOverlap * 0.30 + areaRatio * 0.10
    }

    private fun sameFinalSubject(a: RectD, b: RectD): Boolean {
        val overlap = iou(a, b)
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return overlap >= 0.86 && areaRatio >= 0.62 && dx <= 0.12 && dy <= 0.12
    }

    private fun usefulBox(box: RectD, src: Bitmap): Boolean {
        if (box.width < 4.0 || box.height < 4.0) return false
        val widthFraction = box.width / src.width.toDouble()
        val heightFraction = box.height / src.height.toDouble()
        val areaFraction = box.area / (src.width.toDouble() * src.height.toDouble())
        val aspect = box.width / box.height.coerceAtLeast(1.0)

        if (areaFraction < MIN_BOX_AREA_FRACTION) return false
        if (widthFraction > 0.92 && heightFraction > 0.92) return false
        if (aspect < 0.10 || aspect > 6.5) return false
        return true
    }

    private fun failureMarker(): List<RectD> = listOf(RectD(-10.0, -10.0, -9.0, -9.0))

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
        val left = r.left.coerceIn(0.0, width.toDouble())
        val top = r.top.coerceIn(0.0, height.toDouble())
        val right = r.right.coerceIn(left, width.toDouble())
        val bottom = r.bottom.coerceIn(top, height.toDouble())
        return if (right - left >= 2.0 && bottom - top >= 2.0) RectD(left, top, right, bottom) else null
    }

    private fun union(a: RectD, b: RectD): RectD = RectD(
        min(a.left, b.left),
        min(a.top, b.top),
        max(a.right, b.right),
        max(a.bottom, b.bottom),
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
        private const val TAG = "AutoPersonCropConsensus"

        private const val WHOLE_STRONG_CONFIDENCE = 0.13f
        private const val WHOLE_SOFT_CONFIDENCE = 0.07f
        private const val ROTATED_CONFIDENCE = 0.07f
        private const val STRIP_CONFIDENCE = 0.055f
        private const val GRID_CONFIDENCE = 0.045f
        private const val FOCUS_CONFIDENCE = 0.060f
        private const val EXPLICIT_MIN_CONFIDENCE = 0.055f

        private const val STRIP_FRACTION = 0.70
        private const val MIN_BOX_AREA_FRACTION = 0.0010
        private const val CLUSTER_AFFINITY_THRESHOLD = 0.56
        private const val SECOND_SCORE_RATIO = 0.30
        private const val SINGLE_VIEW_SECOND_SCORE_RATIO = 0.18
        private const val MIN_SECOND_AREA_RATIO = 0.055
        private const val MAX_PARTNER_GAP = 0.28
        private const val MAX_REFINEMENT_AREA_GROWTH = 1.75
        private const val DETECTOR_SAFETY = 0.025

        private const val PASS_WHOLE_STRONG = 0
        private const val PASS_WHOLE_SOFT = 1
        private const val PASS_ROTATED_90 = 2
        private const val PASS_ROTATED_MINUS_90 = 3
        private const val PASS_STRIP_BASE = 10
        private const val PASS_GRID_BASE = 20
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
