package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry-only crop planner for wrestling photos.
 *
 * The source image is never rotated here. We only choose the crop frame orientation:
 * - one clearly standing person -> portrait 2:3;
 * - two clearly standing, non-overlapping people -> portrait 2:3;
 * - one lying/non-upright person -> landscape 3:2;
 * - two people where at least one is non-upright, or the pair strongly overlaps -> landscape 3:2.
 *
 * The subjects passed here have already been selected by WrestlingSubjectSelector. This planner
 * never re-selects people and never switches to the opposite aspect just because it is tighter.
 */
object WrestlingCropPlanner {
    private const val PORTRAIT_ASPECT = 2.0 / 3.0
    private const val LANDSCAPE_ASPECT = 3.0 / 2.0
    private const val STANDING_HEIGHT_TO_WIDTH = 1.10
    private const val WRESTLING_OVERLAP_OF_SMALLER = 0.12
    private const val EPS = 1e-6

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val subject = union(subjects).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val requestedMargin = if (subjects.size >= 2) 0.075 else 0.060
        val margins = listOf(requestedMargin, 0.055, 0.040, 0.025, 0.010, 0.0)
            .filter { it <= requestedMargin + EPS }
            .distinct()

        val layout = chooseRequiredLayout(subjects)
        val aspect = if (layout == SubjectLayout.PORTRAIT) PORTRAIT_ASPECT else LANDSCAPE_ASPECT
        return firstFitting(image, subject, layout, aspect, margins, requestedMargin)?.rect ?: full(image)
    }

    private data class Candidate(
        val rect: PixelRect,
        val layout: SubjectLayout,
        val cropArea: Double,
        val margin: Double,
        val requestedMargin: Double,
    )

    private fun chooseRequiredLayout(subjects: List<RectD>): SubjectLayout {
        if (subjects.size == 1) {
            return if (isStanding(subjects.first())) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
        }

        val a = subjects[0]
        val b = subjects[1]
        val bothStanding = isStanding(a) && isStanding(b)
        val stronglyOverlapping = overlapOfSmaller(a, b) >= WRESTLING_OVERLAP_OF_SMALLER

        return if (bothStanding && !stronglyOverlapping) {
            SubjectLayout.PORTRAIT
        } else {
            SubjectLayout.LANDSCAPE
        }
    }

    private fun isStanding(subject: RectD): Boolean =
        subject.height >= subject.width * STANDING_HEIGHT_TO_WIDTH

    private fun overlapOfSmaller(a: RectD, b: RectD): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        return intersection / min(a.area, b.area).coerceAtLeast(1.0)
    }

    private fun firstFitting(
        image: ImageSize,
        subject: RectD,
        layout: SubjectLayout,
        aspect: Double,
        margins: List<Double>,
        requestedMargin: Double,
    ): Candidate? {
        for (margin in margins) {
            val required = expand(subject, image, margin)
            val placed = placeFrame(image, required, subject, aspect) ?: continue
            return Candidate(
                rect = placed,
                layout = layout,
                cropArea = placed.width.toDouble() * placed.height.toDouble(),
                margin = margin,
                requestedMargin = requestedMargin,
            )
        }
        return null
    }

    private fun expand(subject: RectD, image: ImageSize, margin: Double): RectD {
        // Slightly larger vertical guard protects heads/feet; the detector box may be tight during motion.
        val mx = max(subject.width * margin, image.width * 0.006)
        val my = max(subject.height * (margin + 0.010), image.height * 0.006)
        return RectD(
            subject.left - mx,
            subject.top - my,
            subject.right + mx,
            subject.bottom + my,
        ).clampTo(image)
    }

    private fun placeFrame(
        image: ImageSize,
        required: RectD,
        subject: RectD,
        aspect: Double,
    ): PixelRect? {
        var width = required.width
        var height = required.height
        if (width / height < aspect) width = height * aspect else height = width / aspect
        if (width > image.width + EPS || height > image.height + EPS) return null

        val maxImageLeft = image.width - width
        val maxImageTop = image.height - height
        val minLeft = max(0.0, required.right - width)
        val maxLeft = min(required.left, maxImageLeft)
        val minTop = max(0.0, required.bottom - height)
        val maxTop = min(required.top, maxImageTop)
        if (minLeft > maxLeft + EPS || minTop > maxTop + EPS) return null

        val preferredLeft = subject.centerX - width / 2.0
        val preferredTop = subject.centerY - height / 2.0
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
