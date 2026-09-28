package kz.autopersoncrop.batch

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kz.autopersoncrop.core.RectD
import kz.autopersoncrop.ml.PersonDetector
import kz.autopersoncrop.ml.YoloLiteRtPersonDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detector for the controlled input rule: the frame contains only the wanted one person or two
 * wrestlers. There are no spectators, coaches or referees to reject.
 *
 * Because of that, recall is more important than requiring the same person to be confirmed by two
 * independent views. A standing person can come from the normal pass, while a lying wrestler may be
 * visible only after a 90-degree detector view or inside an enlarged tile. Any plausible person box
 * is useful evidence. We merge repeated evidence, reject only frame-like garbage, and return the
 * small set of boxes that belongs to the same action area.
 *
 * The source image itself is never rotated. Temporary rotated detector views are mapped back to the
 * original coordinates before crop planning.
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
        val rect: RectD,
        val score: Double,
        val passCount: Int,
    )

    override fun detect(bitmap: Bitmap): List<RectD> {
        val observations = ArrayList<Observation>()

        // Whole-frame views.
        observations += observe(
            delegate.detect(bitmap, WHOLE_STRONG_CONFIDENCE),
            bitmap,
            PASS_WHOLE_STRONG,
            4.0,
        )
        observations += observe(
            delegate.detect(bitmap, WHOLE_SOFT_CONFIDENCE),
            bitmap,
            PASS_WHOLE_SOFT,
            2.7,
        )

        // A generic person model is weakest on lying/rotated wrestling poses. These views are not an
        // emergency fallback any more; they are a normal part of recognition for this material.
        observations += detectRotated(bitmap, 90, PASS_ROTATED_90)
        observations += detectRotated(bitmap, -90, PASS_ROTATED_MINUS_90)
        observations += detectRotated(bitmap, 180, PASS_ROTATED_180)

        // Enlarged views help when two wrestlers overlap or occupy a small part of the original.
        observations += detectOverlappingStrips(bitmap)
        observations += detectOverlappingGrid(bitmap)

        if (observations.isEmpty()) {
            Log.w(TAG, "No person observations in any detector view")
            return emptyList()
        }

        val ranked = buildClusters(observations)
            .mapNotNull { rankCluster(it, bitmap) }
            .sortedByDescending { it.score }

        if (ranked.isEmpty()) {
            Log.w(TAG, "All person observations were rejected as implausible")
            return emptyList()
        }

        val primary = ranked.first()
        val selected = ArrayList<RectD>()
        selected += primary.rect

        // We do not have to decide perfectly whether every box is person #1 or person #2. Crop
        // planning only needs the complete action envelope. Keep a few non-duplicate boxes that are
        // physically in the same action area; their union safely covers one or both wrestlers.
        for (candidate in ranked.drop(1)) {
            if (selected.size >= MAX_ACTION_BOXES) break
            if (selected.any { sameFinalSubject(it, candidate.rect) }) continue
            if (candidate.score < primary.score * MIN_RELATED_SCORE_RATIO) continue
            if (!belongsToSameAction(primary.rect, candidate.rect, bitmap)) continue
            selected += candidate.rect
        }

        val result = selected
            .map { addSmallDetectorSafety(it, bitmap) }
            .filter { usefulBox(it, bitmap) }

        Log.i(
            TAG,
            "person views=${observations.size}, clusters=${ranked.size}, kept=${result.size}, boxes=${result.joinToString()}",
        )
        return result
    }

    override fun detect(bitmap: Bitmap, minConfidence: Float): List<RectD> {
        val threshold = maxOf(minConfidence, EXPLICIT_MIN_CONFIDENCE)
        return delegate.detect(bitmap, threshold)
            .filter { usefulBox(it, bitmap) }
            .sortedByDescending { it.area }
            .take(MAX_ACTION_BOXES)
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
                        180 -> RectD(
                            src.width - box.right,
                            src.height - box.bottom,
                            src.width - box.left,
                            src.height - box.top,
                        )
                        else -> return@mapNotNull null
                    }
                    clamp(mapped, src.width, src.height)
                }
                .filter { usefulBox(it, src) }
                .map { Observation(it, passId, 2.4) }
        } finally {
            if (rotated !== src && !rotated.isRecycled) rotated.recycle()
        }
    }

    private fun detectOverlappingStrips(src: Bitmap): List<Observation> {
        val horizontal = src.width >= src.height
        val longSide = if (horizontal) src.width else src.height
        if (longSide < 320) return emptyList()

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
                        ?.let { found += Observation(it, PASS_STRIP_BASE + index, 1.8) }
                }
            } finally {
                if (tile !== src && !tile.isRecycled) tile.recycle()
            }
        }
        return found
    }

    private fun detectOverlappingGrid(src: Bitmap): List<Observation> {
        if (src.width < 260 || src.height < 200) return emptyList()

        val landscape = src.width >= src.height
        val tileW = (src.width * if (landscape) 0.64 else 0.86).roundToInt().coerceIn(1, src.width)
        val tileH = (src.height * if (landscape) 0.86 else 0.64).roundToInt().coerceIn(1, src.height)
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
                            ?.let { found += Observation(it, pass, 1.35) }
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

            if (best == null) clusters += CandidateCluster(mutableListOf(observation))
            else best.observations += observation
        }
        return clusters
    }

    private fun rankCluster(cluster: CandidateCluster, src: Bitmap): RankedCandidate? {
        if (cluster.observations.isEmpty()) return null
        val rect = representative(cluster)
        if (!usefulBox(rect, src)) return null

        val imageArea = src.width.toDouble() * src.height.toDouble()
        val areaFraction = rect.area / imageArea.coerceAtLeast(1.0)
        val passWeights = cluster.observations
            .groupBy { it.passId }
            .mapValues { (_, values) -> values.maxOf { it.weight } }
        val passCount = passWeights.size
        val support = passWeights.values.sum()

        // A single rotated/tile observation is valid in this controlled dataset. Multi-view support
        // still ranks higher, but it is no longer mandatory.
        val sizeBonus = sqrt(areaFraction.coerceIn(0.0, 0.65)) * 1.6
        val score = support + passCount * 0.50 + sizeBonus
        return RankedCandidate(rect, score, passCount)
    }

    private fun belongsToSameAction(primary: RectD, candidate: RectD, src: Bitmap): Boolean {
        val areaRatio = min(primary.area, candidate.area) /
            max(primary.area, candidate.area).coerceAtLeast(1.0)
        if (areaRatio < MIN_RELATED_AREA_RATIO) return false
        if (overlapFractionOfSmaller(primary, candidate) >= 0.01) return true

        val horizontalGap = when {
            primary.right < candidate.left -> candidate.left - primary.right
            candidate.right < primary.left -> primary.left - candidate.right
            else -> 0.0
        }
        val verticalGap = when {
            primary.bottom < candidate.top -> candidate.top - primary.bottom
            candidate.bottom < primary.top -> primary.top - candidate.bottom
            else -> 0.0
        }
        val normalizedGap = sqrt(
            (horizontalGap / src.width.toDouble()).let { it * it } +
                (verticalGap / src.height.toDouble()).let { it * it }
        )
        return normalizedGap <= MAX_ACTION_GAP
    }

    /**
     * Cluster representative deliberately uses robust outer quantiles rather than a pure median.
     * It keeps head/feet/hands seen by only some passes while ignoring a single wild outlier when
     * several passes agree.
     */
    private fun representative(cluster: CandidateCluster): RectD {
        val observations = cluster.observations
        if (observations.size == 1) return observations.first().rect

        return RectD(
            quantile(observations.map { it.rect.left }, 0.20),
            quantile(observations.map { it.rect.top }, 0.20),
            quantile(observations.map { it.rect.right }, 0.80),
            quantile(observations.map { it.rect.bottom }, 0.80),
        )
    }

    private fun quantile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val index = ((sorted.lastIndex) * fraction).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun clusterAffinity(a: RectD, b: RectD): Double {
        val overlap = iou(a, b)
        val smallerOverlap = overlapFractionOfSmaller(a, b)
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)

        if (dx > 0.55 || dy > 0.55) return 0.0
        return overlap * 0.50 + smallerOverlap * 0.35 + areaRatio * 0.15
    }

    private fun sameFinalSubject(a: RectD, b: RectD): Boolean {
        val overlap = iou(a, b)
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return overlap >= 0.82 && areaRatio >= 0.55 && dx <= 0.16 && dy <= 0.16
    }

    private fun usefulBox(box: RectD, src: Bitmap): Boolean {
        if (box.width < 4.0 || box.height < 4.0) return false
        val imageArea = src.width.toDouble() * src.height.toDouble()
        val widthFraction = box.width / src.width.toDouble()
        val heightFraction = box.height / src.height.toDouble()
        val areaFraction = box.area / imageArea.coerceAtLeast(1.0)
        val aspect = box.width / box.height.coerceAtLeast(1.0)

        if (areaFraction < MIN_BOX_AREA_FRACTION) return false
        // This is the important false-positive guard: a vague low-confidence box covering almost the
        // whole source is not a person. A real lying body may span nearly all width, so width alone is
        // never rejected.
        if (areaFraction > MAX_FRAME_LIKE_AREA_FRACTION) return false
        if (widthFraction > 0.97 && heightFraction > 0.88) return false
        if (aspect < 0.07 || aspect > 8.0) return false
        return true
    }

    private fun addSmallDetectorSafety(rect: RectD, src: Bitmap): RectD {
        val mx = max(rect.width * DETECTOR_SAFETY, src.width * 0.002)
        val my = max(rect.height * DETECTOR_SAFETY, src.height * 0.002)
        return clamp(
            RectD(rect.left - mx, rect.top - my, rect.right + mx, rect.bottom + my),
            src.width,
            src.height,
        ) ?: rect
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
        val left = r.left.coerceIn(0.0, width.toDouble())
        val top = r.top.coerceIn(0.0, height.toDouble())
        val right = r.right.coerceIn(left, width.toDouble())
        val bottom = r.bottom.coerceIn(top, height.toDouble())
        return if (right - left >= 2.0 && bottom - top >= 2.0) RectD(left, top, right, bottom) else null
    }

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
        private const val TAG = "AutoPersonCropAction"

        private const val WHOLE_STRONG_CONFIDENCE = 0.14f
        private const val WHOLE_SOFT_CONFIDENCE = 0.055f
        private const val ROTATED_CONFIDENCE = 0.045f
        private const val STRIP_CONFIDENCE = 0.040f
        private const val GRID_CONFIDENCE = 0.035f
        private const val EXPLICIT_MIN_CONFIDENCE = 0.040f

        private const val STRIP_FRACTION = 0.70
        private const val MIN_BOX_AREA_FRACTION = 0.0010
        private const val MAX_FRAME_LIKE_AREA_FRACTION = 0.84
        private const val CLUSTER_AFFINITY_THRESHOLD = 0.43
        private const val MIN_RELATED_AREA_RATIO = 0.025
        private const val MIN_RELATED_SCORE_RATIO = 0.10
        private const val MAX_ACTION_GAP = 0.55
        private const val DETECTOR_SAFETY = 0.010
        private const val MAX_ACTION_BOXES = 4

        private const val PASS_WHOLE_STRONG = 0
        private const val PASS_WHOLE_SOFT = 1
        private const val PASS_ROTATED_90 = 2
        private const val PASS_ROTATED_MINUS_90 = 3
        private const val PASS_ROTATED_180 = 4
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
