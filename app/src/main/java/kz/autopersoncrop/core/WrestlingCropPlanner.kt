package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Crop planner for controlled material that contains only the wanted person/people.
 *
 * The detector geometry is the only anchor. We never stretch, squeeze, rotate or force-centre the
 * picture. We only move crop boundaries.
 *
 * Portrait (standing / sitting / kneeling):
 *  - leave 5% of the FINAL crop as empty space above the detected people;
 *  - leave 5% of the FINAL crop as empty space below them;
 *  - crop left/right as tightly as possible while keeping a normal portrait shape and the complete
 *    detected people.
 *
 * Landscape (lying / wrestling / clearly horizontal action):
 *  - leave 5% of the FINAL crop as empty space left of the detected people;
 *  - leave 5% of the FINAL crop as empty space right of them;
 *  - crop top/bottom as tightly as possible while keeping a normal landscape shape and the complete
 *    detected people.
 *
 * When a person touches a real source edge, the source edge wins: the body is never cut just to
 * manufacture a 5% margin that does not exist in the original.
 */
object WrestlingCropPlanner {
    private const val FINAL_MARGIN_FRACTION = 0.05
    private const val SECONDARY_AXIS_SAFETY = 0.04
    private const val MIN_IMAGE_SAFETY = 0.003

    // Prevents an unnaturally thin strip. It is only a crop-shape guard, never image scaling.
    private const val MAX_LONG_TO_SHORT_RATIO = 2.0

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
        // A clearly horizontal combined action is always landscape, even if one partial detector box
        // happens to look vertical.
        if (group.width >= group.height * 1.30) return Orientation.LANDSCAPE

        // Standing, sitting and kneeling detections are normally taller than wide. One convincing
        // vertical observation is enough because the source is guaranteed to contain no bystanders.
        if (subjects.any { it.height >= it.width * 1.05 }) return Orientation.PORTRAIT

        // Near-square human groups look more natural as portrait. Only clearly wider action becomes
        // landscape.
        return if (group.height >= group.width * 0.90) Orientation.PORTRAIT
        else Orientation.LANDSCAPE
    }

    private fun portraitCrop(image: ImageSize, group: RectD): RectD {
        val verticalMargin = finalFivePercentMargin(group.height)
        val top = (group.top - verticalMargin).coerceAtLeast(0.0)
        val bottom = (group.bottom + verticalMargin).coerceAtMost(image.height.toDouble())
        val height = (bottom - top).coerceAtLeast(1.0)

        val sideSafety = max(group.width * SECONDARY_AXIS_SAFETY, image.width * MIN_IMAGE_SAFETY)
        val requiredLeft = (group.left - sideSafety).coerceAtLeast(0.0)
        val requiredRight = (group.right + sideSafety).coerceAtMost(image.width.toDouble())
        val requiredWidth = (requiredRight - requiredLeft).coerceAtLeast(1.0)

        // A portrait may be tight around the body, but never a pencil-thin strip.
        val targetWidth = max(requiredWidth, height / MAX_LONG_TO_SHORT_RATIO)
            .coerceAtMost(image.width.toDouble())

        val horizontal = placeWindowPreservingSourcePosition(
            sourceSize = image.width.toDouble(),
            targetSize = targetWidth,
            anchorCenter = group.centerX,
            requiredMin = requiredLeft,
            requiredMax = requiredRight,
        )
        return RectD(horizontal.first, top, horizontal.second, bottom)
    }

    private fun landscapeCrop(image: ImageSize, group: RectD): RectD {
        val horizontalMargin = finalFivePercentMargin(group.width)
        val left = (group.left - horizontalMargin).coerceAtLeast(0.0)
        val right = (group.right + horizontalMargin).coerceAtMost(image.width.toDouble())
        val width = (right - left).coerceAtLeast(1.0)

        val verticalSafety = max(group.height * SECONDARY_AXIS_SAFETY, image.height * MIN_IMAGE_SAFETY)
        val requiredTop = (group.top - verticalSafety).coerceAtLeast(0.0)
        val requiredBottom = (group.bottom + verticalSafety).coerceAtMost(image.height.toDouble())
        val requiredHeight = (requiredBottom - requiredTop).coerceAtLeast(1.0)

        // Keep enough height for a normal landscape photograph, but do not inflate it to 16:9 when
        // the detected action itself is much flatter. This was the source of excessive empty space.
        val targetHeight = max(requiredHeight, width / MAX_LONG_TO_SHORT_RATIO)
            .coerceAtMost(image.height.toDouble())

        val vertical = placeWindowPreservingSourcePosition(
            sourceSize = image.height.toDouble(),
            targetSize = targetHeight,
            anchorCenter = group.centerY,
            requiredMin = requiredTop,
            requiredMax = requiredBottom,
        )
        return RectD(left, vertical.first, right, vertical.second)
    }

    /**
     * If the visible people occupy S pixels and each margin must be exactly 5% of the final crop F:
     * F = S + 2*0.05F, therefore one margin = S/18.
     */
    private fun finalFivePercentMargin(subjectSize: Double): Double =
        subjectSize * FINAL_MARGIN_FRACTION / (1.0 - 2.0 * FINAL_MARGIN_FRACTION)

    /**
     * Preserve the subject's position in the original image. This is deliberately not centring.
     */
    private fun placeWindowPreservingSourcePosition(
        sourceSize: Double,
        targetSize: Double,
        anchorCenter: Double,
        requiredMin: Double,
        requiredMax: Double,
    ): Pair<Double, Double> {
        if (targetSize >= sourceSize) return 0.0 to sourceSize

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
