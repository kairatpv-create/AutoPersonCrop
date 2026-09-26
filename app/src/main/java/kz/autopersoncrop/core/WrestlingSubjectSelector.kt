package kz.autopersoncrop.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selector for the new controlled input rule: every source frame contains ONLY one person or two
 * wrestlers. There are no spectators, referees or coaches to reject.
 *
 * Therefore we deliberately avoid all former scene ranking/centrality logic. We only:
 *  1) merge repeated observations of the same person from detector passes;
 *  2) discard tiny boxes that are obviously fragments inside a larger person box;
 *  3) return the one remaining person or the two largest independent people.
 */
object WrestlingSubjectSelector {
    fun select(image: ImageSize, people: List<RectD>): List<RectD> {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val valid = people
            .map { it.clampTo(image) }
            .filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
        if (valid.isEmpty()) return listOf(people.first().clampTo(image))

        val merged = mergeRepeatedObservations(valid)
            .sortedByDescending { it.area }
        if (merged.size == 1) return merged

        val primary = merged.first()
        val second = merged.drop(1).firstOrNull { !isObviousFragmentOf(it, primary) }
        return if (second == null) listOf(primary)
        else listOf(primary, second)
    }

    fun isFullyVisible(image: ImageSize, r: RectD): Boolean {
        val edgeX = max(3.0, image.width * FULL_VISIBILITY_EDGE_FRACTION)
        val edgeY = max(3.0, image.height * FULL_VISIBILITY_EDGE_FRACTION)
        return r.left > edgeX &&
            r.right < image.width - edgeX &&
            r.top > edgeY &&
            r.bottom < image.height - edgeY
    }

    private fun mergeRepeatedObservations(input: List<RectD>): List<RectD> {
        val merged = ArrayList<RectD>()
        for (candidate in input.sortedByDescending { it.area }) {
            val index = merged.indices
                .filter { samePersonObservation(merged[it], candidate) }
                .maxByOrNull { overlapIoU(merged[it], candidate) }
            if (index == null) {
                merged += candidate
            } else {
                merged[index] = union(merged[index], candidate)
            }
        }
        return merged
    }

    /** Strict enough to keep two genuinely overlapping wrestlers as two people. */
    private fun samePersonObservation(a: RectD, b: RectD): Boolean {
        val iou = overlapIoU(a, b)
        if (iou >= 0.94) return true
        if (iou < 0.84) return false

        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.74) return false

        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.085 && dy <= 0.085
    }

    /**
     * Detector recovery can occasionally return a torso/head fragment in addition to the full person.
     * With no background people, only a strongly contained, small, close-centred box is discarded.
     */
    private fun isObviousFragmentOf(candidate: RectD, larger: RectD): Boolean {
        if (candidate.area > larger.area * 0.42) return false
        val containment = overlapFractionOfSmaller(candidate, larger)
        if (containment < 0.82) return false
        return normalizedCenterDistance(candidate, larger) <= 0.32
    }

    private fun normalizedCenterDistance(a: RectD, b: RectD): Double {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        val scaleW = max(a.width, b.width).coerceAtLeast(1.0)
        val scaleH = max(a.height, b.height).coerceAtLeast(1.0)
        val scale = sqrt(scaleW * scaleW + scaleH * scaleH).coerceAtLeast(1.0)
        return sqrt(dx * dx + dy * dy) / scale
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

    private fun overlapIoU(a: RectD, b: RectD): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }

    private fun union(a: RectD, b: RectD): RectD = RectD(
        min(a.left, b.left),
        min(a.top, b.top),
        max(a.right, b.right),
        max(a.bottom, b.bottom),
    )

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }

    private const val FULL_VISIBILITY_EDGE_FRACTION = 0.006
}
