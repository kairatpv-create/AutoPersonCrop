package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Crop planner for controlled source frames that contain only one person or two wrestlers.
 *
 * Rules:
 * - crop only: never stretch, squeeze, scale, rotate or recenter the people;
 * - portrait: normally trim 5% from source top and bottom, unless that would cut a body;
 * - landscape: normally trim 5% from source left and right, unless that would cut a body;
 * - the opposite pair of edges is chosen only to make a natural portrait/landscape frame;
 * - all people stay fully inside the crop with a small safety margin.
 */
object WrestlingCropPlanner {
    private const val SOURCE_TRIM = 0.05
    private const val BODY_SAFETY = 0.025
    private const val MIN_IMAGE_SAFETY = 0.006

    private const val PORTRAIT_WIDTH_TO_HEIGHT = 0.75
    private const val LANDSCAPE_WIDTH_TO_HEIGHT = 1.70

    private enum class Orientation { PORTRAIT, LANDSCAPE }

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one person is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
            .take(2)
        require(clean.isNotEmpty()) { "Не удалось построить рамку по человеку" }

        val group = union(clean).clampTo(image)
        val protected = protect(group, image)
        val orientation = chooseOrientation(clean, group)

        val crop = when (orientation) {
            Orientation.PORTRAIT -> portraitCrop(image, group, protected)
            Orientation.LANDSCAPE -> landscapeCrop(image, group, protected)
        }
        return crop.clampTo(image).toPixelRect(image)
    }

    private fun chooseOrientation(subjects: List<RectD>, group: RectD): Orientation {
        val effectivelySingle = subjects.size == 1 ||
            (subjects.size >= 2 && overlapIoU(subjects[0], subjects[1]) >= 0.98)

        if (effectivelySingle) {
            return if (group.height >= group.width * 1.05) Orientation.PORTRAIT
            else Orientation.LANDSCAPE
        }

        val standing = subjects.count { it.height >= it.width * 1.15 }
        return if (standing == subjects.size && group.height >= group.width * 0.72) {
            Orientation.PORTRAIT
        } else if (group.height > group.width * 1.03) {
            Orientation.PORTRAIT
        } else {
            Orientation.LANDSCAPE
        }
    }

    private fun protect(group: RectD, image: ImageSize): RectD {
        val mx = max(group.width * BODY_SAFETY, image.width * MIN_IMAGE_SAFETY)
        val my = max(group.height * BODY_SAFETY, image.height * MIN_IMAGE_SAFETY)
        return RectD(
            group.left - mx,
            group.top - my,
            group.right + mx,
            group.bottom + my,
        ).clampTo(image)
    }

    private fun portraitCrop(image: ImageSize, group: RectD, protected: RectD): RectD {
        val desiredTop = image.height * SOURCE_TRIM
        val desiredBottom = image.height * (1.0 - SOURCE_TRIM)

        val top = min(desiredTop, protected.top)
        val bottom = max(desiredBottom, protected.bottom)
        val height = (bottom - top).coerceAtLeast(1.0)

        val requiredWidth = protected.width
        val targetWidth = max(height * PORTRAIT_WIDTH_TO_HEIGHT, requiredWidth)
            .coerceAtMost(image.width.toDouble())

        val horizontal = placeWindowPreservingSourcePosition(
            sourceSize = image.width.toDouble(),
            targetSize = targetWidth,
            anchorCenter = group.centerX,
            requiredMin = protected.left,
            requiredMax = protected.right,
        )

        return RectD(horizontal.first, top, horizontal.second, bottom)
    }

    private fun landscapeCrop(image: ImageSize, group: RectD, protected: RectD): RectD {
        val desiredLeft = image.width * SOURCE_TRIM
        val desiredRight = image.width * (1.0 - SOURCE_TRIM)

        val left = min(desiredLeft, protected.left)
        val right = max(desiredRight, protected.right)
        val width = (right - left).coerceAtLeast(1.0)

        val requiredHeight = protected.height
        val targetHeight = max(width / LANDSCAPE_WIDTH_TO_HEIGHT, requiredHeight)
            .coerceAtMost(image.height.toDouble())

        val vertical = placeWindowPreservingSourcePosition(
            sourceSize = image.height.toDouble(),
            targetSize = targetHeight,
            anchorCenter = group.centerY,
            requiredMin = protected.top,
            requiredMax = protected.bottom,
        )

        return RectD(left, vertical.first, right, vertical.second)
    }

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
