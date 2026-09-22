package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry-only crop planner for wrestling photos.
 *
 * The source image is never rotated. Only the crop frame is portrait 2:3 or landscape 3:2.
 * Rules:
 * - one standing/upright person -> portrait;
 * - two standing/upright people, including a standing wrestling clinch -> portrait;
 * - one lying/horizontal person -> landscape;
 * - two lying people or a ground-wrestling pair -> landscape.
 *
 * A short sequence history may hold the previous orientation only when the current geometry is
 * ambiguous. Strong current pose evidence always wins, so a real transition from standing to ground
 * action is not blocked.
 */
object WrestlingCropPlanner {
    private const val PORTRAIT_ASPECT = 2.0 / 3.0
    private const val LANDSCAPE_ASPECT = 3.0 / 2.0
    private const val UPRIGHT_HEIGHT_TO_WIDTH = 1.08
    private const val STRONG_PORTRAIT_RATIO = 1.24
    private const val STRONG_LANDSCAPE_RATIO = 0.90
    private const val EPS = 1e-6

    fun plan(
        image: ImageSize,
        subjects: List<RectD>,
        preferredLayout: SubjectLayout? = null,
    ): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val subject = union(subjects).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val detectedLayout = classifyLayout(subjects)
        val requestedLayout = preferredLayout ?: detectedLayout
        frameFor(image, subject, subjects.size, requestedLayout)?.let { return it }

        if (requestedLayout != detectedLayout) {
            frameFor(image, subject, subjects.size, detectedLayout)?.let { return it }
        }
        return full(image)
    }

    fun classifyLayout(subjects: List<RectD>): SubjectLayout {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }
        if (subjects.size == 1) {
            return if (isUpright(subjects.first())) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
        }
        val principal = subjects.take(2)
        return if (principal.all(::isUpright)) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
    }

    fun hasStrongLayoutEvidence(subjects: List<RectD>, layout: SubjectLayout): Boolean {
        if (subjects.isEmpty()) return false
        val principal = subjects.take(2)
        return when (layout) {
            SubjectLayout.PORTRAIT -> principal.all {
                it.height / it.width.coerceAtLeast(1.0) >= STRONG_PORTRAIT_RATIO
            }
            SubjectLayout.LANDSCAPE -> {
                principal.any { it.height / it.width.coerceAtLeast(1.0) <= STRONG_LANDSCAPE_RATIO } ||
                    union(principal).let { it.width >= it.height * 1.12 }
            }
        }
    }

    private fun frameFor(
        image: ImageSize,
        subject: RectD,
        count: Int,
        layout: SubjectLayout,
    ): PixelRect? = when (layout) {
        SubjectLayout.PORTRAIT -> portraitFrame(image, subject, count)
        SubjectLayout.LANDSCAPE -> landscapeFrame(image, subject, count)
    }

    private fun isUpright(subject: RectD): Boolean =
        subject.height >= subject.width * UPRIGHT_HEIGHT_TO_WIDTH

    private fun portraitFrame(image: ImageSize, subject: RectD, count: Int): PixelRect? {
        val primaryMargin = if (count >= 2) 0.065 else 0.060
        val attempts = listOf(primaryMargin, 0.050, 0.035, 0.020, 0.010, 0.0).distinct()
        for (margin in attempts) {
            val sideMargin = min(0.025, margin)
            val required = expandDirectional(subject, image, sideMargin, margin)
            placePortrait(image, required, subject)?.let { return it }
        }
        return null
    }

    private fun landscapeFrame(image: ImageSize, subject: RectD, count: Int): PixelRect? {
        val primaryMargin = if (count >= 2) 0.060 else 0.055
        val attempts = listOf(primaryMargin, 0.050, 0.035, 0.020, 0.010, 0.0).distinct()
        for (margin in attempts) {
            val verticalMargin = min(0.025, margin)
            val required = expandDirectional(subject, image, margin, verticalMargin)
            placeLandscape(image, required, subject)?.let { return it }
        }
        return null
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
        var width = required.width
        var height = width / LANDSCAPE_ASPECT
        if (height < required.height) {
            height = required.height
            width = height * LANDSCAPE_ASPECT
        }
        return placeFrame(image, required, subject, width, height, verticalBias = -0.03)
    }

    private fun placePortrait(image: ImageSize, required: RectD, subject: RectD): PixelRect? {
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
