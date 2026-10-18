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
 *  - leave about 4–5% of the FINAL crop above and below the people;
 *  - crop left/right by the actual people envelope, guided by the natural manual reference
 *    shape without enforcing one aspect ratio or a minimum crop area.
 *
 * Landscape rule (lying / wrestling / clearly horizontal action):
 *  - leave about 4–5% of the FINAL crop left and right of the people;
 *  - crop top/bottom by the actual action envelope, with a soft landscape shape guide
 *    capped by body height. The nearer source side anchors the composition.
 *
 * Posture chooses the main margin axis. Two standing people do NOT become a
 * landscape scene merely because they are standing far apart and their combined envelope is wide.
 */
object WrestlingCropPlanner {
    // Across the 29 registered manual crops, unobstructed top/bottom space
    // clusters around 3–5% of the final frame. Keep this once, in the planner.
    private const val FINAL_MARGIN_FRACTION = 0.04
    private const val SECONDARY_AXIS_SAFETY = 0.06
    private const val MIN_IMAGE_SAFETY = 0.003

    // A slender upright body needs a little lateral balance, but a shape hint
    // may add at most half its detected width. There is no fixed output ratio.
    private const val SLENDER_PORTRAIT_SHAPE = 0.57
    private const val MAX_PORTRAIT_WIDTH_SCALE = 1.50
    private const val LANDSCAPE_SHAPE_HINT = 1.60
    private const val MAX_LANDSCAPE_HEIGHT_SCALE = 1.25

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

        val guideWidth = min(height * SLENDER_PORTRAIT_SHAPE, group.width * MAX_PORTRAIT_WIDTH_SCALE)
        val targetWidth = max(requiredWidth, guideWidth).coerceAtMost(image.width.toDouble())

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

        val guideHeight = min(width / LANDSCAPE_SHAPE_HINT, group.height * MAX_LANDSCAPE_HEIGHT_SCALE)
        val targetHeight = max(requiredHeight, guideHeight).coerceAtMost(image.height.toDouble())

        val vertical = placeSecondaryWindow(
            sourceSize = image.height.toDouble(),
            targetSize = targetHeight,
            anchorCenter = group.centerY,
            requiredMin = requiredTop,
            requiredMax = requiredBottom,
            anchorNearestEdge = true,
        )
        return RectD(left, vertical.first, right, vertical.second)
    }

    private fun primaryAxisBounds(subjectMin: Double, subjectMax: Double, sourceSize: Double): Pair<Double, Double> {
        val desired = finalMargin((subjectMax - subjectMin).coerceAtLeast(1.0))
        // A source-clipped side cannot supply its margin. Do not transfer that
        // missing space to the opposite side and bring the background back.
        return max(0.0, subjectMin - desired) to min(sourceSize, subjectMax + desired)
    }

    private fun finalMargin(subjectSize: Double): Double =
        subjectSize * FINAL_MARGIN_FRACTION / (1.0 - 2.0 * FINAL_MARGIN_FRACTION)

    private fun placeSecondaryWindow(
        sourceSize: Double,
        targetSize: Double,
        anchorCenter: Double,
        requiredMin: Double,
        requiredMax: Double,
        anchorNearestEdge: Boolean = false,
    ): Pair<Double, Double> {
        if (targetSize >= sourceSize) return 0.0 to sourceSize

        if (anchorNearestEdge) {
            // Start at the side with less unused source space. Keep the small
            // safety margin there, then remove background on the other side.
            // Never pin to an image edge when a valid subject margin fits.
            val start = if (requiredMin <= sourceSize - requiredMax) {
                requiredMin
            } else {
                requiredMax - targetSize
            }.coerceIn(0.0, sourceSize - targetSize)
            return start to start + targetSize
        }

        // For an upright body, balance the small lateral slack about the body,
        // then clamp to the source without transferring unavailable padding.
        var start = anchorCenter - targetSize / 2.0
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
