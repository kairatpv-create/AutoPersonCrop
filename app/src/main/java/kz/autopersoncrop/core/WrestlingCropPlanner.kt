package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry-only crop planner for wrestling photos.
 *
 * The subjects passed here have already been selected by WrestlingSubjectSelector. This planner
 * never re-selects people. It only removes empty space around the one/two principal wrestlers,
 * preserves the whole selected subject area, and chooses the tighter valid 2:3 or 3:2 frame.
 */
object WrestlingCropPlanner {
    private const val PORTRAIT_ASPECT = 2.0 / 3.0
    private const val LANDSCAPE_ASPECT = 3.0 / 2.0
    private const val EPS = 1e-6

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val subject = union(subjects).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val requestedMargin = if (subjects.size >= 2) 0.075 else 0.060
        val margins = listOf(requestedMargin, 0.055, 0.040, 0.025, 0.010, 0.0)
            .filter { it <= requestedMargin + EPS }
            .distinct()

        val portrait = firstFitting(image, subject, SubjectLayout.PORTRAIT, PORTRAIT_ASPECT, margins, requestedMargin)
        val landscape = firstFitting(image, subject, SubjectLayout.LANDSCAPE, LANDSCAPE_ASPECT, margins, requestedMargin)

        val winner = choose(subject, subjects, portrait, landscape) ?: return full(image)
        return winner.rect
    }

    private data class Candidate(
        val rect: PixelRect,
        val layout: SubjectLayout,
        val cropArea: Double,
        val margin: Double,
        val requestedMargin: Double,
    )

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

    private fun choose(
        subject: RectD,
        subjects: List<RectD>,
        portrait: Candidate?,
        landscape: Candidate?,
    ): Candidate? {
        if (portrait == null) return landscape
        if (landscape == null) return portrait

        val ratio = subject.width / subject.height.coerceAtLeast(1.0)
        if (ratio >= 1.22 && landscape.margin + EPS >= portrait.margin) return landscape
        if (ratio <= 0.82 && portrait.margin + EPS >= landscape.margin) return portrait

        val portraitScore = score(subject, subjects, portrait)
        val landscapeScore = score(subject, subjects, landscape)
        return if (portraitScore <= landscapeScore) portrait else landscape
    }

    private fun score(subject: RectD, subjects: List<RectD>, c: Candidate): Double {
        val subjectArea = subject.area.coerceAtLeast(1.0)
        var score = c.cropArea / subjectArea
        score += (c.requestedMargin - c.margin).coerceAtLeast(0.0) * 7.0

        val groupRatio = subject.width / subject.height.coerceAtLeast(1.0)
        if (groupRatio > 1.10 && c.layout == SubjectLayout.PORTRAIT) score += 0.25
        if (groupRatio < 0.90 && c.layout == SubjectLayout.LANDSCAPE) score += 0.25

        if (subjects.size >= 2) {
            val xSpan = subjects.maxOf { it.centerX } - subjects.minOf { it.centerX }
            val ySpan = subjects.maxOf { it.centerY } - subjects.minOf { it.centerY }
            if (xSpan > ySpan * 1.15 && c.layout == SubjectLayout.PORTRAIT) score += 0.18
            if (ySpan > xSpan * 1.15 && c.layout == SubjectLayout.LANDSCAPE) score += 0.14
        } else {
            val body = subjects.first()
            if (body.width > body.height * 1.15 && c.layout == SubjectLayout.PORTRAIT) score += 0.20
            if (body.height > body.width * 1.15 && c.layout == SubjectLayout.LANDSCAPE) score += 0.16
        }
        return score
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

        // Choose a frame position near the subject centre, while mathematically guaranteeing that
        // the complete required area remains inside the frame after shifting at image edges.
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
