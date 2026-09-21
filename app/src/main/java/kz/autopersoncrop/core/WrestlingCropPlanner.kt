package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry-only crop planner for wrestling photos.
 *
 * The source image is never rotated. Only the crop frame is portrait 2:3 or landscape 3:2.
 * Rules for 0.6.11:
 * - one standing/upright person -> portrait;
 * - two standing/upright people, including a standing wrestling clinch -> portrait;
 * - one lying/horizontal person -> landscape;
 * - two lying people or a ground-wrestling pair -> landscape.
 *
 * Landscape composition is driven by the pair's left/right bounds first; top/bottom are then fitted
 * naturally to 3:2. Portrait composition is driven by the vertical bounds first; side edges are then
 * fitted naturally to 2:3. Selected subjects are never intentionally cut by this planner.
 */
object WrestlingCropPlanner {
    private const val PORTRAIT_ASPECT = 2.0 / 3.0
    private const val LANDSCAPE_ASPECT = 3.0 / 2.0
    private const val UPRIGHT_HEIGHT_TO_WIDTH = 1.08
    private const val EPS = 1e-6

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val subject = union(subjects).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        return when (chooseRequiredLayout(subjects)) {
            SubjectLayout.PORTRAIT -> portraitFrame(image, subject, subjects.size)
            SubjectLayout.LANDSCAPE -> landscapeFrame(image, subject, subjects.size)
        }
    }

    /**
     * A standing wrestling clinch remains portrait even when the two detector boxes overlap heavily.
     * Overlap by itself no longer forces landscape. Ground action becomes landscape because at least
     * one principal body is no longer clearly upright.
     */
    private fun chooseRequiredLayout(subjects: List<RectD>): SubjectLayout {
        if (subjects.size == 1) {
            return if (isUpright(subjects.first())) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
        }

        val principal = subjects.take(2)
        return if (principal.all(::isUpright)) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
    }

    private fun isUpright(subject: RectD): Boolean =
        subject.height >= subject.width * UPRIGHT_HEIGHT_TO_WIDTH

    /**
     * Portrait: preserve sensible head/feet breathing room first. Width is derived afterwards from
     * the fixed 2:3 aspect. This is used for one/two standing people and standing wrestling.
     */
    private fun portraitFrame(image: ImageSize, subject: RectD, count: Int): PixelRect {
        val primaryMargin = if (count >= 2) 0.065 else 0.060
        val attempts = listOf(primaryMargin, 0.050, 0.035, 0.020, 0.010, 0.0).distinct()
        for (margin in attempts) {
            val sideMargin = min(0.025, margin)
            val required = expandDirectional(
                subject = subject,
                image = image,
                horizontalMargin = sideMargin,
                verticalMargin = margin,
            )
            placePortrait(image, required, subject)?.let { return it }
        }
        return full(image)
    }

    /**
     * Landscape: left/right bounds of the lying/ground-wrestling action are the primary constraint.
     * Top/bottom use a smaller safety margin and are positioned afterwards to make a natural 3:2
     * sports frame without unnecessary empty space.
     */
    private fun landscapeFrame(image: ImageSize, subject: RectD, count: Int): PixelRect {
        val primaryMargin = if (count >= 2) 0.060 else 0.055
        val attempts = listOf(primaryMargin, 0.050, 0.035, 0.020, 0.010, 0.0).distinct()
        for (margin in attempts) {
            val verticalMargin = min(0.025, margin)
            val required = expandDirectional(
                subject = subject,
                image = image,
                horizontalMargin = margin,
                verticalMargin = verticalMargin,
            )
            placeLandscape(image, required, subject)?.let { return it }
        }
        return full(image)
    }

    private fun expandDirectional(
        subject: RectD,
        image: ImageSize,
        horizontalMargin: Double,
        verticalMargin: Double,
    ): RectD {
        val mx = max(subject.width * horizontalMargin, image.width * 0.004)
        val my = max(subject.height * verticalMargin, image.height * 0.004)
        return RectD(
            subject.left - mx,
            subject.top - my,
            subject.right + mx,
            subject.bottom + my,
        ).clampTo(image)
    }

    private fun placeLandscape(image: ImageSize, required: RectD, subject: RectD): PixelRect? {
        // Start from left/right action bounds. Increase only if the required vertical extent needs it.
        var width = required.width
        var height = width / LANDSCAPE_ASPECT
        if (height < required.height) {
            height = required.height
            width = height * LANDSCAPE_ASPECT
        }
        return placeFrame(image, required, subject, width, height, verticalBias = -0.03)
    }

    private fun placePortrait(image: ImageSize, required: RectD, subject: RectD): PixelRect? {
        // Start from top/bottom action bounds. Increase only if the required horizontal extent needs it.
        var height = required.height
        var width = height * PORTRAIT_ASPECT
        if (width < required.width) {
            width = required.width
            height = width / PORTRAIT_ASPECT
        }
        return placeFrame(image, required, subject, width, height, verticalBias = -0.015)
    }

    private fun placeFrame(
        image: ImageSize,
        required: RectD,
        subject: RectD,
        width: Double,
        height: Double,
        verticalBias: Double,
    ): PixelRect? {
        if (width > image.width + EPS || height > image.height + EPS) return null

        val maxImageLeft = image.width - width
        val maxImageTop = image.height - height
        val minLeft = max(0.0, required.right - width)
        val maxLeft = min(required.left, maxImageLeft)
        val minTop = max(0.0, required.bottom - height)
        val maxTop = min(required.top, maxImageTop)
        if (minLeft > maxLeft + EPS || minTop > maxTop + EPS) return null

        val preferredLeft = subject.centerX - width / 2.0
        // A tiny upward bias leaves slightly more useful space above the athletes than below them.
        val preferredTop = subject.centerY - height / 2.0 + height * verticalBias
        val left = preferredLeft.coerceIn(minLeft, maxLeft)
        val top = preferredTop.coerceIn(minTop, maxTop)
        val right = left + width
        val bottom = top + height

        val px = PixelRect(
            left = floor(left).toInt().coerceIn(0, image.width - 1),
            top = floor(top).toInt().coerceIn(0, image.height - 1),
            right = ceil(right).toInt().coerceIn(1, image.width),
            bottom = ceil(bottom).toInt().coerceIn(1, image.height),
        )
        if (px.width < 2 || px.height < 2) return null
        return px
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom },
    )

    private fun RectD.clampTo(image: ImageSize): RectD = RectD(
        left.coerceIn(0.0, image.width.toDouble()),
        top.coerceIn(0.0, image.height.toDouble()),
        right.coerceIn(0.0, image.width.toDouble()),
        bottom.coerceIn(0.0, image.height.toDouble()),
    )

    private fun full(image: ImageSize) = PixelRect(0, 0, image.width, image.height)
}
