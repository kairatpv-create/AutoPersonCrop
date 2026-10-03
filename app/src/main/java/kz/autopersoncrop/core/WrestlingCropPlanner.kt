package kz.autopersoncrop.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tight people-first crop planner for controlled material with only one wanted person or two wrestlers.
 *
 * Every crop side is calculated independently from the confirmed people envelope. There is no
 * balancing, centring, opposite-side compensation or target aspect ratio. Empty background is removed
 * unless it is the small breathing room requested around the people.
 *
 * Portrait posture (standing / sitting / kneeling):
 *  - about 5% of the FINAL crop above and below the people;
 *  - only a small safety margin left and right.
 *
 * Landscape posture (lying / wrestling / clearly horizontal action):
 *  - about 5% of the FINAL crop left and right of the people;
 *  - only a small safety margin top and bottom.
 *
 * If a real image edge prevents a requested margin, that side simply uses the pixels that exist.
 * Missing margin is NEVER transferred to the opposite side.
 */
object WrestlingCropPlanner {
    private const val FINAL_MARGIN_FRACTION = 0.05
    private const val SECONDARY_FINAL_MARGIN_FRACTION = 0.03
    private const val EDGE_TOUCH_FRACTION = 0.012

    private enum class Orientation { PORTRAIT, LANDSCAPE }

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one person is required" }

        val clean = subjects
            .take(2)
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        require(clean.isNotEmpty()) { "Не удалось построить рамку по человеку" }

        val physical = selectPhysicalSubjects(image, clean)
        val group = union(physical).clampTo(image)
        require(group.width >= 2.0 && group.height >= 2.0) { "Пустая область людей" }

        val crop = when (chooseOrientation(physical, group)) {
            Orientation.PORTRAIT -> portraitCrop(image, group)
            Orientation.LANDSCAPE -> landscapeCrop(image, group)
        }
        return crop.clampTo(image).toPixelRect(image)
    }

    /**
     * PhotoProcessor passes detector candidates in ranked order. The first one is the main physical
     * person/action. A second candidate is kept unless it has the geometry of a weak duplicate/edge
     * fragment. This prevents one stray detector box from stretching one crop side to the source edge.
     */
    private fun selectPhysicalSubjects(image: ImageSize, clean: List<RectD>): List<RectD> {
        if (clean.size == 1) return clean
        val primary = clean[0]
        val second = clean[1]
        return if (likelyDuplicateOrEdgeFragment(image, primary, second)) listOf(primary)
        else listOf(primary, second)
    }

    private fun likelyDuplicateOrEdgeFragment(image: ImageSize, primary: RectD, second: RectD): Boolean {
        val areaRatio = min(primary.area, second.area) / max(primary.area, second.area).coerceAtLeast(1.0)
        if (areaRatio < 0.07) return true

        val overlap = overlapFractionOfSmaller(primary, second)
        val dx = abs(primary.centerX - second.centerX) / max(primary.width, second.width).coerceAtLeast(1.0)
        val dy = abs(primary.centerY - second.centerY) / max(primary.height, second.height).coerceAtLeast(1.0)

        // Same person found by two passes with slightly different outer bounds.
        if (overlap >= 0.68 && dx <= 0.35 && dy <= 0.35 && areaRatio < 0.78) return true

        val secondTouchesEdge = touchesAnyEdge(second, image)
        if (!secondTouchesEdge) return false

        val primaryTouchesSameEdge = touchesSameSourceEdge(primary, second, image)
        if (primaryTouchesSameEdge) return false

        // Small edge fragments are the most common reason one whole side remains uncut.
        if (areaRatio < 0.32) return true

        val primaryPortrait = primary.height >= primary.width * 1.20
        val primaryLandscape = primary.width >= primary.height * 1.25
        val secondPortrait = second.height >= second.width * 1.20
        val secondLandscape = second.width >= second.height * 1.25
        val strongShapeMismatch = (primaryPortrait && secondLandscape) || (primaryLandscape && secondPortrait)
        if (strongShapeMismatch && areaRatio < 0.72) return true

        val secondCenterInsidePrimary =
            second.centerX in primary.left..primary.right && second.centerY in primary.top..primary.bottom
        if (secondCenterInsidePrimary && areaRatio < 0.80) return true

        if (overlap >= 0.25 && areaRatio < 0.55) return true
        return false
    }

    private fun chooseOrientation(subjects: List<RectD>, group: RectD): Orientation {
        var portraitEvidence = 0.0
        var landscapeEvidence = 0.0
        for (subject in subjects) {
            val weight = sqrt(subject.area.coerceAtLeast(1.0))
            when {
                subject.height >= subject.width * 1.08 -> portraitEvidence += weight
                subject.width >= subject.height * 1.15 -> landscapeEvidence += weight
                subject.height >= subject.width -> portraitEvidence += weight * 0.40
                else -> landscapeEvidence += weight * 0.40
            }
        }

        if (portraitEvidence > 0.0 && portraitEvidence >= landscapeEvidence * 0.85) {
            return Orientation.PORTRAIT
        }
        if (landscapeEvidence > portraitEvidence * 1.08) {
            return Orientation.LANDSCAPE
        }
        return if (group.width >= group.height * 1.16) Orientation.LANDSCAPE
        else Orientation.PORTRAIT
    }

    private fun portraitCrop(image: ImageSize, group: RectD): RectD {
        val vertical = independentAxisBounds(
            subjectMin = group.top,
            subjectMax = group.bottom,
            sourceSize = image.height.toDouble(),
            fraction = FINAL_MARGIN_FRACTION,
        )
        val horizontal = independentAxisBounds(
            subjectMin = group.left,
            subjectMax = group.right,
            sourceSize = image.width.toDouble(),
            fraction = SECONDARY_FINAL_MARGIN_FRACTION,
        )
        return RectD(horizontal.first, vertical.first, horizontal.second, vertical.second)
    }

    private fun landscapeCrop(image: ImageSize, group: RectD): RectD {
        val horizontal = independentAxisBounds(
            subjectMin = group.left,
            subjectMax = group.right,
            sourceSize = image.width.toDouble(),
            fraction = FINAL_MARGIN_FRACTION,
        )
        val vertical = independentAxisBounds(
            subjectMin = group.top,
            subjectMax = group.bottom,
            sourceSize = image.height.toDouble(),
            fraction = SECONDARY_FINAL_MARGIN_FRACTION,
        )
        return RectD(horizontal.first, vertical.first, horizontal.second, vertical.second)
    }

    /** Each side is calculated independently. No missing margin is moved to the opposite side. */
    private fun independentAxisBounds(
        subjectMin: Double,
        subjectMax: Double,
        sourceSize: Double,
        fraction: Double,
    ): Pair<Double, Double> {
        val subjectSize = (subjectMax - subjectMin).coerceAtLeast(1.0)
        val desired = finalMargin(subjectSize, fraction)
        val before = min(desired, subjectMin.coerceAtLeast(0.0))
        val after = min(desired, (sourceSize - subjectMax).coerceAtLeast(0.0))
        return (subjectMin - before).coerceAtLeast(0.0) to
            (subjectMax + after).coerceAtMost(sourceSize)
    }

    /**
     * If people occupy S and each normal margin is fraction P of final crop F:
     * F = S + 2*P*F, therefore one margin = S*P/(1-2P).
     */
    private fun finalMargin(subjectSize: Double, fraction: Double): Double =
        subjectSize * fraction / (1.0 - 2.0 * fraction)

    private fun touchesAnyEdge(r: RectD, image: ImageSize): Boolean {
        val x = image.width * EDGE_TOUCH_FRACTION
        val y = image.height * EDGE_TOUCH_FRACTION
        return r.left <= x || r.right >= image.width - x || r.top <= y || r.bottom >= image.height - y
    }

    private fun touchesSameSourceEdge(a: RectD, b: RectD, image: ImageSize): Boolean {
        val x = image.width * EDGE_TOUCH_FRACTION
        val y = image.height * EDGE_TOUCH_FRACTION
        return (a.left <= x && b.left <= x) ||
            (a.right >= image.width - x && b.right >= image.width - x) ||
            (a.top <= y && b.top <= y) ||
            (a.bottom >= image.height - y && b.bottom >= image.height - y)
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

    private fun RectD.toPixelRect(image: ImageSize): PixelRect {
        val l = floor(left).toInt().coerceIn(0, image.width - 1)
        val t = floor(top).toInt().coerceIn(0, image.height - 1)
        val r = ceil(right).toInt().coerceIn(l + 1, image.width)
        val b = ceil(bottom).toInt().coerceIn(t + 1, image.height)
        return PixelRect(l, t, r, b)
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        rects.minOf { it.left },
        rects.minOf { it.top },
        rects.maxOf { it.right },
        rects.maxOf { it.bottom },
    )

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }
}
