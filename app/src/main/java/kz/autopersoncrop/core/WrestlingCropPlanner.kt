package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Edge-aware free-aspect crop planner for wrestling photos.
 *
 * Phone-test behaviour:
 * - keep roughly 5% breathing room around the selected main person / pair;
 * - never crop inside the selected subject geometry, protecting head and feet;
 * - use the same rule for standing, kneeling and ground/lying action;
 * - if the action is already at a source edge, keep that edge instead of re-centering the people;
 * - do not force a minimum crop area, which would leave excessive empty background.
 *
 * The planner never rotates, stretches, centres or pads the image.
 */
object WrestlingCropPlanner {
    // 5.56% of the detected subject produces about a 5% border in the resulting frame when both
    // opposite margins are available: margin / (subject + 2 * margin) ~= 0.05.
    private const val SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME = 0.0556
    private const val SOURCE_EDGE_FRACTION = 0.012

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (clean.isEmpty()) return full(image)

        val subject = union(clean).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        // Do not classify standing/lying people differently here. The subject selector has already
        // chosen the main action. The crop simply follows that geometry with the same ~5% breathing
        // room on every free side. A side that is already at the source edge remains at that edge;
        // we never shift the frame just to centre the person or pair.
        val required = expandDirectional(
            subject = subject,
            image = image,
            leftFraction = SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME,
            rightFraction = SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME,
            topFraction = SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME,
            bottomFraction = SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME,
        )

        return required.toPixelRect(image)
    }

    private fun expandDirectional(
        subject: RectD,
        image: ImageSize,
        leftFraction: Double,
        rightFraction: Double,
        topFraction: Double,
        bottomFraction: Double,
    ): RectD {
        // Margins are derived only from the selected subject size. Do not impose an image-size based
        // minimum: for small/edge subjects that minimum was the reason some 0.7.2 crops retained far
        // more than the requested 5% empty background.
        val leftMargin = subject.width * leftFraction
        val rightMargin = subject.width * rightFraction
        val topMargin = subject.height * topFraction
        val bottomMargin = subject.height * bottomFraction

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
