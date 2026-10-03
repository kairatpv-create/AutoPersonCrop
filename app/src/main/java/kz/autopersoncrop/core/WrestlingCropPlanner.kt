package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tight crop planner for controlled material that contains only the wanted one person or two wrestlers.
 *
 * The people envelope is the only composition anchor. There is no target aspect ratio and no attempt
 * to make a portrait/landscape look more conventional by adding empty background. We only keep the
 * breathing room requested around the detected people.
 *
 * Portrait posture (standing / sitting / kneeling):
 *  - about 5% of the FINAL crop above and below the people;
 *  - only a small safety margin left/right.
 *
 * Landscape posture (lying / wrestling / clearly horizontal action):
 *  - about 5% of the FINAL crop left and right of the people;
 *  - only a small safety margin top/bottom.
 *
 * If a real source edge blocks a requested margin, the person is never moved or centred. That side
 * simply keeps the available pixels; a limited part of the missing breathing room may be placed on
 * the opposite side only on the primary 5% axis.
 */
object WrestlingCropPlanner {
    private const val FINAL_MARGIN_FRACTION = 0.05
    private const val SECONDARY_FINAL_MARGIN_FRACTION = 0.04
    private const val EDGE_MISSING_MARGIN_TRANSFER = 0.60

    private enum class Orientation { PORTRAIT, LANDSCAPE }

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one person is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        require(clean.isNotEmpty()) { "Не удалось построить рамку по человеку" }

        val group = union(clean).clampTo(image)
        require(group.width >= 2.0 && group.height >= 2.0) { "Пустая область людей" }

        val crop = when (chooseOrientation(clean, group)) {
            Orientation.PORTRAIT -> portraitCrop(image, group)
            Orientation.LANDSCAPE -> landscapeCrop(image, group)
        }
        return crop.clampTo(image).toPixelRect(image)
    }

    private fun chooseOrientation(subjects: List<RectD>, group: RectD): Orientation {
        // Human posture has priority. Two standing people do not become a landscape scene merely
        // because there is a large horizontal gap between them.
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
        val vertical = primaryAxisBounds(
            subjectMin = group.top,
            subjectMax = group.bottom,
            sourceSize = image.height.toDouble(),
        )
        val sideMargin = finalMargin(group.width, SECONDARY_FINAL_MARGIN_FRACTION)
        val left = (group.left - sideMargin).coerceAtLeast(0.0)
        val right = (group.right + sideMargin).coerceAtMost(image.width.toDouble())
        return RectD(left, vertical.first, right, vertical.second)
    }

    private fun landscapeCrop(image: ImageSize, group: RectD): RectD {
        val horizontal = primaryAxisBounds(
            subjectMin = group.left,
            subjectMax = group.right,
            sourceSize = image.width.toDouble(),
        )
        val verticalMargin = finalMargin(group.height, SECONDARY_FINAL_MARGIN_FRACTION)
        val top = (group.top - verticalMargin).coerceAtLeast(0.0)
        val bottom = (group.bottom + verticalMargin).coerceAtMost(image.height.toDouble())
        return RectD(horizontal.first, top, horizontal.second, bottom)
    }

    /**
     * Main 5% axis. When one real source edge blocks the requested margin, use what is available and
     * transfer only part of the missing space to the opposite side. Full compensation would centre
     * the person/group and is intentionally forbidden.
     */
    private fun primaryAxisBounds(
        subjectMin: Double,
        subjectMax: Double,
        sourceSize: Double,
    ): Pair<Double, Double> {
        val subjectSize = (subjectMax - subjectMin).coerceAtLeast(1.0)
        val desired = finalMargin(subjectSize, FINAL_MARGIN_FRACTION)
        val availableBefore = subjectMin.coerceAtLeast(0.0)
        val availableAfter = (sourceSize - subjectMax).coerceAtLeast(0.0)

        var before = min(desired, availableBefore)
        var after = min(desired, availableAfter)

        val missingBefore = (desired - before).coerceAtLeast(0.0)
        val missingAfter = (desired - after).coerceAtLeast(0.0)

        if (missingBefore > 0.0) {
            val extraRoom = (availableAfter - after).coerceAtLeast(0.0)
            after += min(extraRoom, missingBefore * EDGE_MISSING_MARGIN_TRANSFER)
        }
        if (missingAfter > 0.0) {
            val extraRoom = (availableBefore - before).coerceAtLeast(0.0)
            before += min(extraRoom, missingAfter * EDGE_MISSING_MARGIN_TRANSFER)
        }

        return (subjectMin - before).coerceAtLeast(0.0) to
            (subjectMax + after).coerceAtMost(sourceSize)
    }

    /**
     * If visible people occupy S and each margin is fraction P of final crop F:
     * F = S + 2*P*F, so one requested margin = S*P/(1-2P).
     */
    private fun finalMargin(subjectSize: Double, fraction: Double): Double =
        subjectSize * fraction / (1.0 - 2.0 * fraction)

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
