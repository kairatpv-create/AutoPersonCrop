package kz.autopersoncrop.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Selector for controlled input: there is exactly one person or two wrestlers in the source frame,
 * and no unrelated people. Therefore we do not rank by image centre or try to reject referees.
 * We only remove repeated observations of the same person, then keep up to two independent boxes.
 *
 * For a genuine single-person frame we intentionally return the same box twice. Downstream code
 * then treats the frame as complete and does not pull a second wrestler from sequence memory.
 */
object WrestlingSubjectSelector {
    fun select(image: ImageSize, people: List<RectD>): List<RectD> {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val valid = people
            .map { it.clampTo(image) }
            .filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
            .sortedByDescending { it.area }

        if (valid.isEmpty()) {
            val first = people.first().clampTo(image)
            return listOf(first, first)
        }

        val primary = valid.first()
        val second = valid.asSequence()
            .drop(1)
            .filter { !sameObservation(primary, it) }
            .filter { it.area >= primary.area * MIN_SECOND_AREA_RATIO }
            .maxByOrNull { secondCandidateScore(primary, it) }

        return if (second != null) listOf(primary, second) else listOf(primary, primary)
    }

    fun isFullyVisible(image: ImageSize, r: RectD): Boolean {
        val edgeX = max(2.0, image.width * 0.006)
        val edgeY = max(2.0, image.height * 0.006)
        return r.left > edgeX &&
            r.right < image.width - edgeX &&
            r.top > edgeY &&
            r.bottom < image.height - edgeY
    }

    private fun secondCandidateScore(primary: RectD, candidate: RectD): Double {
        val areaRatio = (candidate.area / primary.area.coerceAtLeast(1.0)).coerceIn(0.0, 1.5)
        val overlap = overlapIoU(primary, candidate)
        val dx = abs(primary.centerX - candidate.centerX) / max(primary.width, candidate.width).coerceAtLeast(1.0)
        val dy = abs(primary.centerY - candidate.centerY) / max(primary.height, candidate.height).coerceAtLeast(1.0)
        val separation = (dx + dy).coerceAtMost(2.0)
        return areaRatio * 0.70 + separation * 0.22 - overlap * 0.18
    }

    /** Strict duplicate test: preserve two wrestlers even when they overlap strongly. */
    private fun sameObservation(a: RectD, b: RectD): Boolean {
        val overlap = overlapIoU(a, b)
        if (overlap >= 0.94) return true
        if (overlap < 0.84) return false

        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.76) return false

        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.10 && dy <= 0.10
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

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }

    private const val MIN_SECOND_AREA_RATIO = 0.08
}
