package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Crop planner for controlled source frames containing only one person or two wrestlers.
 *
 * The image is never scaled, stretched, squeezed, rotated or force-centred. Only crop boundaries
 * move. Portrait and landscape are photographic shape ranges, not rigid aspect ratios: a rigid
 * ratio previously expanded wide action shots back to the complete source frame.
 *
 * Portrait:
 *  - normally remove 5% from source top and 5% from source bottom;
 *  - if a body occupies that zone, move that edge outward to keep the body;
 *  - choose only as much side width as is needed for a natural portrait.
 *
 * Landscape:
 *  - normally remove 5% from source left and 5% from source right;
 *  - if a body occupies that zone, move that edge outward to keep the body;
 *  - choose only as much top/bottom height as is needed for a natural landscape.
 */
object WrestlingCropPlanner {
    private const val SOURCE_TRIM = 0.05
    private const val BODY_SAFETY = 0.025
    private const val MIN_IMAGE_SAFETY = 0.006

    // These are composition guides, not mandatory output ratios.
    private const val PORTRAIT_GUIDE_ASPECT = 0.75
    private const val PORTRAIT_MAX_SOURCE_WIDTH = 0.88
    private const val LANDSCAPE_GUIDE_ASPECT = 16.0 / 9.0
    private const val LANDSCAPE_MAX_SOURCE_HEIGHT = 0.90

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
        if (subjects.size == 1) {
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

        // Do not widen all the way to the source just to hit a fixed portrait ratio.
        val guidedWidth = min(
            height * PORTRAIT_GUIDE_ASPECT,
            image.width * PORTRAIT_MAX_SOURCE_WIDTH,
        )
        val targetWidth = max(guidedWidth, protected.width)
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

        // A wide pair may legitimately force left/right to the source edges. Previously width/1.70
        // could then demand the full source height. Cap the composition guide at 90% of source height;
        // protected body geometry can still override the cap when necessary.
        val guidedHeight = min(
            width / LANDSCAPE_GUIDE_ASPECT,
            image.height * LANDSCAPE_MAX_SOURCE_HEIGHT,
        )
        val targetHeight = max(guidedHeight, protected.height)
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

    /**
     * Preserve the subject's relative source position. This is intentionally not centring: if the
     * person is left, right, high or low in the source, the crop keeps that visual bias whenever the
     * image boundaries allow it.
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
