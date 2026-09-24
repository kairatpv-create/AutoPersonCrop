package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Tight free-aspect crop planner for wrestling photos.
 *
 * Agreed phone-test behaviour:
 * - one standing person: about 5% above and below, sides kept compact;
 * - two standing people: the same rule around the combined group;
 * - wide/ground wrestling (two lying people or one sitting on another): landscape composition,
 *   anchored from the side where the source already has less empty space, with the opposite side
 *   fitted around the complete action;
 * - no artificial minimum crop area. A valid main subject must not be surrounded by large amounts
 *   of background just to satisfy a percentage of the original image.
 *
 * The planner never rotates, stretches or pads the image.
 */
object WrestlingCropPlanner {
    private const val VERTICAL_MARGIN = 0.050
    private const val PORTRAIT_SIDE_MARGIN = 0.030
    private const val LANDSCAPE_NEAR_SIDE_MARGIN = 0.030
    private const val LANDSCAPE_FAR_SIDE_MARGIN = 0.050
    private const val MIN_IMAGE_MARGIN = 0.008
    private const val SOURCE_EDGE_FRACTION = 0.012

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (clean.isEmpty()) return full(image)

        val subject = union(clean).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val portraitScene = isStandingPortraitScene(clean)
        val required = if (portraitScene) {
            // Standing portrait: fixed ~5% head/foot breathing room. Side margins are deliberately
            // smaller because the user wants the crop to follow the body instead of the room.
            expandDirectional(
                subject = subject,
                image = image,
                leftFraction = PORTRAIT_SIDE_MARGIN,
                rightFraction = PORTRAIT_SIDE_MARGIN,
                topFraction = VERTICAL_MARGIN,
                bottomFraction = VERTICAL_MARGIN,
            )
        } else {
            // Ground/wide action: start from the side which already has less empty source space.
            // That side gets the tighter margin; the opposite side gets the normal 5% allowance.
            val leftGap = subject.left
            val rightGap = image.width.toDouble() - subject.right
            val anchorLeft = leftGap <= rightGap
            expandDirectional(
                subject = subject,
                image = image,
                leftFraction = if (anchorLeft) LANDSCAPE_NEAR_SIDE_MARGIN else LANDSCAPE_FAR_SIDE_MARGIN,
                rightFraction = if (anchorLeft) LANDSCAPE_FAR_SIDE_MARGIN else LANDSCAPE_NEAR_SIDE_MARGIN,
                topFraction = VERTICAL_MARGIN,
                bottomFraction = VERTICAL_MARGIN,
            )
        }

        return required.toPixelRect(image)
    }

    /**
     * A single clearly vertical body is a standing portrait subject. Two clearly vertical bodies are
     * treated as a standing pair even when their combined union becomes fairly wide. Any other pair
     * (lying, kneeling, overlapping, one sitting on another) follows the wide/action rule.
     */
    private fun isStandingPortraitScene(subjects: List<RectD>): Boolean {
        fun isVerticalBody(r: RectD): Boolean = r.height >= r.width * 1.15
        return when (subjects.size) {
            1 -> isVerticalBody(subjects.first())
            2 -> subjects.all(::isVerticalBody)
            else -> false
        }
    }

    private fun expandDirectional(
        subject: RectD,
        image: ImageSize,
        leftFraction: Double,
        rightFraction: Double,
        topFraction: Double,
        bottomFraction: Double,
    ): RectD {
        val minX = image.width * MIN_IMAGE_MARGIN
        val minY = image.height * MIN_IMAGE_MARGIN
        val leftMargin = max(subject.width * leftFraction, minX)
        val rightMargin = max(subject.width * rightFraction, minX)
        val topMargin = max(subject.height * topFraction, minY)
        val bottomMargin = max(subject.height * bottomFraction, minY)

        val edgeX = image.width * SOURCE_EDGE_FRACTION
        val edgeY = image.height * SOURCE_EDGE_FRACTION
        val left = if (subject.left <= edgeX) 0.0 else subject.left - leftMargin
        val right = if (subject.right >= image.width - edgeX) image.width.toDouble() else subject.right + rightMargin
        val top = if (subject.top <= edgeY) 0.0 else subject.top - topMargin
        val bottom = if (subject.bottom >= image.height - edgeY) image.height.toDouble() else subject.bottom + bottomMargin

        return RectD(left, top, right, bottom).clampTo(image)
    }

    private fun RectD.toPixelRect(image: ImageSize): PixelRect {
        val l = floor(left).toInt().coerceIn(0, image.width - 1)
        val t = floor(top).toInt().coerceIn(0, image.height - 1)
        val r = ceil(right).toInt().coerceIn(l + 1, image.width)
        val b = ceil(bottom).toInt().coerceIn(t + 1, image.height)
        return PixelRect(l, t, r, b)
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom },
    )

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }

    private fun full(image: ImageSize) = PixelRect(0, 0, image.width, image.height)
}
