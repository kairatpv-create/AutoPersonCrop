package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Crop planner for controlled material that contains only the wanted person/people.
 *
 * The detector geometry is the only anchor. We never stretch, squeeze, rotate or force-centre the
 * picture. We only move crop boundaries.
 *
 * Portrait (standing / sitting / kneeling):
 *  - normally leave 5% of the FINAL crop as empty space above and below the detected people;
 *  - crop left/right as tightly as possible while keeping a natural portrait and the complete body.
 *
 * Landscape (lying / wrestling / clearly horizontal action):
 *  - normally leave 5% of the FINAL crop as empty space left and right of the detected people;
 *  - crop top/bottom as tightly as possible while keeping a natural landscape and the complete body.
 *
 * Edge rule:
 *  - 5% is a target, never a reason to cut a person;
 *  - when the group is too close to one real source edge, that side may have less than 5%;
 *  - part of the missing breathing room is moved to the opposite side so the composition does not
 *    feel crushed, but the group is never artificially centred.
 */
object WrestlingCropPlanner {
    private const val FINAL_MARGIN_FRACTION = 0.05
    private const val SECONDARY_AXIS_SAFETY = 0.04
    private const val MIN_IMAGE_SAFETY = 0.003

    // When one primary-axis margin is blocked by a source edge, move only part of the missing space
    // to the opposite side. Full compensation would visually centre the subject, which is forbidden.
    private const val EDGE_MISSING_MARGIN_TRANSFER = 0.60

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
        val vertical = primaryAxisBounds(
            subjectMin = group.top,
            subjectMax = group.bottom,
            sourceSize = image.height.toDouble(),
        )
        val top = vertical.first
        val bottom = vertical.second
        val height = (bottom - top).coerceAtLeast(1.0)

        val sideSafety = max(group.width * SECONDARY_AXIS_SAFETY, image.width * MIN_IMAGE_SAFETY)
        val requiredLeft = (group.left - sideSafety).coerceAtLeast(0.0)
        val requiredRight = (group.right + sideSafety).coerceAtMost(image.width.toDouble())
        val requiredWidth = (requiredRight - requiredLeft).coerceAtLeast(1.0)

        // A portrait may be tight around the body, but never a pencil-thin strip.
        val targetWidth = max(requiredWidth, height / MAX_LONG_TO_SHORT_RATIO)
            .coerceAtMost(image.width.toDouble())

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
        val horizontal = primaryAxisBounds(
            subjectMin = group.left,
            subjectMax = group.right,
            sourceSize = image.width.toDouble(),
        )
        val left = horizontal.first
        val right = horizontal.second
        val width = (right - left).coerceAtLeast(1.0)

        val verticalSafety = max(group.height * SECONDARY_AXIS_SAFETY, image.height * MIN_IMAGE_SAFETY)
        val requiredTop = (group.top - verticalSafety).coerceAtLeast(0.0)
        val requiredBottom = (group.bottom + verticalSafety).coerceAtMost(image.height.toDouble())
        val requiredHeight = (requiredBottom - requiredTop).coerceAtLeast(1.0)

        // Keep enough height for a normal landscape photograph, but do not inflate it to a rigid
        // 16:9 or other fixed aspect ratio.
        val targetHeight = max(requiredHeight, width / MAX_LONG_TO_SHORT_RATIO)
            .coerceAtMost(image.height.toDouble())

        val vertical = placeSecondaryWindow(
            sourceSize = image.height.toDouble(),
            targetSize = targetHeight,
            anchorCenter = group.centerY,
            requiredMin = requiredTop,
            requiredMax = requiredBottom,
        )
        return RectD(left, vertical.first, right, vertical.second)
    }

    /**
     * Normally one margin is exactly 5% of the final crop. If a real source edge blocks one side,
     * use all available pixels there and move only part of the missing breathing room to the other
     * side. This preserves the original visual bias instead of force-centring the group.
     */
    private fun primaryAxisBounds(
        subjectMin: Double,
        subjectMax: Double,
        sourceSize: Double,
    ): Pair<Double, Double> {
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

        return (subjectMin - before).coerceAtLeast(0.0) to
            (subjectMax + after).coerceAtMost(sourceSize)
    }

    /**
     * If the visible people occupy S pixels and each normal margin must be 5% of the final crop F:
     * F = S + 2*0.05F, therefore one margin = S/18.
     */
    private fun finalFivePercentMargin(subjectSize: Double): Double =
        subjectSize * FINAL_MARGIN_FRACTION / (1.0 - 2.0 * FINAL_MARGIN_FRACTION)

    /**
     * Secondary-axis placement keeps the group's original source bias. If the required body envelope
     * is already close to a source edge, that real edge is kept and all extra composition room goes
     * to the other side. Otherwise the source-relative position is preserved. This is deliberately
     * not centring.
     */
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

        if (availableBefore <= edgeThreshold) {
            val start = 0.0
            return start to (start + targetSize).coerceAtMost(sourceSize)
        }
        if (availableAfter <= edgeThreshold) {
            val end = sourceSize
            return (end - targetSize).coerceAtLeast(0.0) to end
        }

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
