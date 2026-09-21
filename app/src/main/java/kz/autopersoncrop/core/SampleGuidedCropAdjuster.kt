package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Applies ОБРАЗЕЦ as a soft framing guide while never cutting detected wrestlers. */
object SampleGuidedCropAdjuster {
    fun adjust(
        image: ImageSize,
        subjects: List<RectD>,
        base: PixelRect,
        profile: SceneCropProfile,
    ): PixelRect {
        if (subjects.isEmpty()) return base
        val style = profile.styleFor(subjects.size) ?: return base
        val subject = union(subjects).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return base

        val baseRect = RectD(
            base.left.toDouble(),
            base.top.toDouble(),
            base.right.toDouble(),
            base.bottom.toDouble(),
        )
        val bw = subject.width.coerceAtLeast(1.0)
        val bh = subject.height.coerceAtLeast(1.0)

        fun blend(sample: Double, current: Double): Double =
            (sample * SAMPLE_WEIGHT + current * (1.0 - SAMPLE_WEIGHT)).coerceIn(MIN_MARGIN_RATIO, MAX_MARGIN_RATIO)

        val leftRatio = blend(style.leftSubjectWidths, (subject.left - baseRect.left) / bw)
        val rightRatio = blend(style.rightSubjectWidths, (baseRect.right - subject.right) / bw)
        val topRatio = blend(style.topSubjectHeights, (subject.top - baseRect.top) / bh)
        val bottomRatio = blend(style.bottomSubjectHeights, (baseRect.bottom - subject.bottom) / bh)

        val desired = RectD(
            subject.left - bw * leftRatio,
            subject.top - bh * topRatio,
            subject.right + bw * rightRatio,
            subject.bottom + bh * bottomRatio,
        ).clampTo(image)

        // Hard safety envelope: ОБРАЗЕЦ can tighten empty space but cannot trim a person box.
        val safe = RectD(
            subject.left - bw * SAFETY_MARGIN,
            subject.top - bh * SAFETY_MARGIN,
            subject.right + bw * SAFETY_MARGIN,
            subject.bottom + bh * SAFETY_MARGIN,
        ).clampTo(image)
        val required = union(listOf(desired, safe)).clampTo(image)

        val baseLandscape = base.width >= base.height
        val useLandscape = when {
            style.landscapeShare >= 0.67 -> true
            style.landscapeShare <= 0.33 -> false
            else -> baseLandscape
        }
        val aspect = if (useLandscape) LANDSCAPE_ASPECT else PORTRAIT_ASPECT
        val guided = fitAspect(image, required, aspect) ?: return base

        return if (contains(guided, subject)) guided else base
    }

    private fun fitAspect(image: ImageSize, required: RectD, aspect: Double): PixelRect? {
        var width = max(required.width, required.height * aspect)
        var height = width / aspect
        if (height < required.height) {
            height = required.height
            width = height * aspect
        }
        if (width > image.width + 0.5 || height > image.height + 0.5) return null

        width = min(width, image.width.toDouble())
        height = min(height, image.height.toDouble())

        val idealLeft = required.centerX - width / 2.0
        val minLeft = max(0.0, required.right - width)
        val maxLeft = min(required.left, image.width - width)
        if (minLeft > maxLeft + 1e-6) return null
        val left = idealLeft.coerceIn(minLeft, maxLeft)

        val idealTop = required.centerY - height / 2.0
        val minTop = max(0.0, required.bottom - height)
        val maxTop = min(required.top, image.height - height)
        if (minTop > maxTop + 1e-6) return null
        val top = idealTop.coerceIn(minTop, maxTop)

        val l = floor(left).toInt().coerceIn(0, image.width - 1)
        val t = floor(top).toInt().coerceIn(0, image.height - 1)
        val r = ceil(left + width).toInt().coerceIn(l + 1, image.width)
        val b = ceil(top + height).toInt().coerceIn(t + 1, image.height)
        return PixelRect(l, t, r, b)
    }

    private fun contains(rect: PixelRect, subject: RectD): Boolean =
        rect.left <= subject.left && rect.top <= subject.top &&
            rect.right >= subject.right && rect.bottom >= subject.bottom

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

    private const val SAMPLE_WEIGHT = 0.72
    private const val SAFETY_MARGIN = 0.05
    private const val MIN_MARGIN_RATIO = 0.04
    private const val MAX_MARGIN_RATIO = 1.50
    private const val PORTRAIT_ASPECT = 2.0 / 3.0
    private const val LANDSCAPE_ASPECT = 3.0 / 2.0
}
