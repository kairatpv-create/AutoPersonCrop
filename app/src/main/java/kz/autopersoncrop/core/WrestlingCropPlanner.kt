package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Crop planner for controlled material that contains only the wanted one person or two wrestlers.
 *
 * The detector geometry is the only anchor. We never stretch, squeeze, rotate or force-centre the
 * picture. We only move crop boundaries.
 *
 * Portrait rule (standing / sitting / kneeling):
 *  - leave about 5% of the FINAL crop as empty space above and below the people;
 *  - crop left/right by the actual people envelope, adding only enough width to avoid a cramped strip.
 *
 * Landscape rule (lying / wrestling / clearly horizontal action):
 *  - leave about 5% of the FINAL crop as empty space left and right of the people;
 *  - crop top/bottom by the actual action envelope, adding only enough height to avoid a cramped strip.
 *
 * The important detail is that posture chooses the 5% axis. Two standing people do NOT become a
 * landscape scene merely because they are standing far apart and their combined envelope is wide.
 */
object WrestlingCropPlanner {
    private const val FINAL_MARGIN_FRACTION = 0.05
    private const val SECONDARY_AXIS_SAFETY = 0.07
    private const val MIN_IMAGE_SAFETY = 0.003

    private const val EDGE_MISSING_MARGIN_TRANSFER = 0.60
    private const val SOFT_LONG_TO_SHORT_GUIDE = 2.0
    private const val SOFT_SHAPE_BLEND = 0.65

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
        var portraitEvidence = 0.0
        var landscapeEvidence = 0.0
        for (subject in subjects) {
            val weight = sqrt(subject.area.coerceAtLeast(1.0))
            when {
                subject.height >= subject.width * 1.10 -> portraitEvidence += weight
                subject.width >= subject.height * 1.18 -> landscapeEvidence += weight
                subject.height >= subject.width -> portraitEvidence += weight * 0.35
                else -> landscapeEvidence += weight * 0.35
            }
        }

        if (portraitEvidence > 0.0 && portraitEvidence >= landscapeEvidence * 0.85) return Orientation.PORTRAIT
        if (landscapeEvidence > portraitEvidence * 1.10) return Orientation.LANDSCAPE
        return if (group.width >= group.height * 1.18) Orientation.LANDSCAPE else Orientation.PORTRAIT
    }

    private fun portraitCrop(image: ImageSize, group: RectD): RectD {
        val vertical = primaryAxisBounds(group.top, group.bottom, image.height.toDouble())
        val top = vertical.first
        val bottom = vertical.second
        val height = (bottom - top).coerceAtLeast(1.0)

        val sideSafety = max(group.width * SECONDARY_AXIS_SAFETY, image.width * MIN_IMAGE_SAFETY)
        val requiredLeft = (group.left - sideSafety).coerceAtLeast(0.0)
        val requiredRight = (group.right + sideSafety).coerceAtMost(image.width.toDouble())
        val requiredWidth = (requiredRight - requiredLeft).coerceAtLeast(1.0)

        val guideWidth = (height / SOFT_LONG_TO_SHORT_GUIDE).coerceAtMost(image.width.toDouble())
        val targetWidth = softExpand(requiredWidth, guideWidth).coerceAtMost(image.width.toDouble())

        val horizontal = placeSecondaryWindow(
            sourceSize = image.width.toDouble(),
            targetSize = targetWidth,
            anchorCenter = group.centerX,
            requiredMin = requiredLeft,
            requiredMax = requiredRight,
        )
        return RectD(horizontal.first, top, horizontal.second, bottom)
    }

    private fun landscapeCrop(image: ImageSize, group: RectD): RectD {
        val horizontal = primaryAxisBounds(group.left, group.right, image.width.toDouble())
        val left = horizontal.first
        val right = horizontal.second
        val width = (right - left).coerceAtLeast(1.0)

        val verticalSafety = max(group.height * SECONDARY_AXIS_SAFETY, image.height * MIN_IMAGE_SAFETY)
        val requiredTop = (group.top - verticalSafety).coerceAtLeast(0.0)
        val requiredBottom = (group.bottom + verticalSafety).coerceAtMost(image.height.toDouble())
        val requiredHeight = (requiredBottom - requiredTop).coerceAtLeast(1.0)

        val guideHeight = (width / SOFT_LONG_TO_SHORT_GUIDE).coerceAtMost(image.height.toDouble())
        val targetHeight = softExpand(requiredHeight, guideHeight).coerceAtMost(image.height.toDouble())

        val vertical = placeSecondaryWindow(
            sourceSize = image.height.toDouble(),
            targetSize = targetHeight,
            anchorCenter = group.centerY,
            requiredMin = requiredTop,
            requiredMax = requiredBottom,
        )
        return RectD(left, vertical.first, right, vertical.second)
    }

    private fun softExpand(required: Double, guide: Double): Double {
        if (required >= guide) return required
        return required + (guide - required) * SOFT_SHAPE_BLEND
    }

    private fun primaryAxisBounds(subjectMin: Double, subjectMax: Double, sourceSize: Double): Pair<Double, Double> {
        val subjectSize = (subjectMax - subjectMin).coerceAtLeast(1.0)
        val desired = finalFivePercentMargin(subjectSize)
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

        return (subjectMin - before).coerceAtLeast(0.0) to (subjectMax + after).coerceAtMost(sourceSize)
    }

    private fun finalFivePercentMargin(subjectSize: Double): Double =
        subjectSize * FINAL_MARGIN_FRACTION / (1.0 - 2.0 * FINAL_MARGIN_FRACTION)

    private fun placeSecondaryWindow(
        sourceSize: Double,
        targetSize: Double,
        anchorCenter: Double,
        requiredMin: Double,
        requiredMax: Double,
    ): Pair<Double, Double> {
        if (targetSize >= sourceSize) return 0.0 to sourceSize

        val availableBefore = requiredMin.coerceAtLeast(0.0)
        val availableAfter = (sourceSize - requiredMax).coerceAtLeast(0.0)
        val extra = (targetSize - (requiredMax - requiredMin)).coerceAtLeast(0.0)
        val edgeThreshold = max(sourceSize * 0.025, extra * 0.35)

        if (availableBefore <= edgeThreshold) return 0.0 to targetSize.coerceAtMost(sourceSize)
        if (availableAfter <= edgeThreshold) return (sourceSize - targetSize).coerceAtLeast(0.0) to sourceSize

        val sourceFraction = (anchorCenter / sourceSize).coerceIn(0.0, 1.0)
        var start = anchorCenter - sourceFraction * targetSize
        start = start.coerceIn(0.0, sourceSize - targetSize)
        var end = start + targetSize

        if (start > requiredMin) {
            start = requiredMin.coerceAtLeast(0.0)
            end = start + targetSize
        }
        if (end < requiredMax) {
            end = requiredMax.coerceAtMost(sourceSize)
            start = end - targetSize
        }

        start = start.coerceIn(0.0, sourceSize - targetSize)
        end = (start + targetSize).coerceAtMost(sourceSize)
        if (start > requiredMin) start = requiredMin.coerceAtLeast(0.0)
        if (end < requiredMax) end = requiredMax.coerceAtMost(sourceSize)
        return start to end
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
