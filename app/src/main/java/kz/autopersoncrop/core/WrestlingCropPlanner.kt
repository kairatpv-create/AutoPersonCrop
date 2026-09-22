package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Free-aspect crop planner for wrestling photos.
 *
 * The frame follows the action instead of forcing 2:3 / 3:2. For a tall composition we protect
 * roughly 5% at the left/right and let top/bottom breathe naturally. For a wide composition we do
 * the opposite. Only soft aspect guards are used so the result cannot become an implausibly thin
 * strip or an excessively wide banner. Selected subjects are never intentionally cut.
 */
object WrestlingCropPlanner {
    private const val PRIMARY_MARGIN = 0.050
    private const val FREE_MARGIN_SINGLE = 0.085
    private const val FREE_MARGIN_PAIR = 0.100
    private const val BALANCED_MARGIN = 0.065
    private const val MIN_IMAGE_MARGIN = 0.010
    private const val SOURCE_EDGE_FRACTION = 0.012

    // Soft limits only. We expand the frame to respect them; we never squeeze or stretch pixels.
    private const val MIN_ASPECT = 0.60
    private const val MAX_ASPECT = 1.85

    // Prevent accidental extreme zoom when one marginal detection survives.
    private const val MIN_SINGLE_AREA_FRACTION = 0.12
    private const val MIN_PAIR_AREA_FRACTION = 0.09
    private const val EPS = 1e-6

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (clean.isEmpty()) return full(image)

        val subject = union(clean).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val ratio = subject.width / subject.height.coerceAtLeast(1.0)
        val freeMargin = if (clean.size >= 2) FREE_MARGIN_PAIR else FREE_MARGIN_SINGLE

        val required = when {
            ratio <= 0.82 -> expandDirectional(
                subject = subject,
                image = image,
                leftFraction = PRIMARY_MARGIN,
                rightFraction = PRIMARY_MARGIN,
                topFraction = freeMargin + 0.015,
                bottomFraction = freeMargin,
            )
            ratio >= 1.22 -> expandDirectional(
                subject = subject,
                image = image,
                leftFraction = freeMargin,
                rightFraction = freeMargin,
                topFraction = PRIMARY_MARGIN,
                bottomFraction = PRIMARY_MARGIN,
            )
            else -> expandDirectional(
                subject = subject,
                image = image,
                leftFraction = BALANCED_MARGIN,
                rightFraction = BALANCED_MARGIN,
                topFraction = BALANCED_MARGIN + 0.010,
                bottomFraction = BALANCED_MARGIN,
            )
        }

        var targetWidth = required.width
        var targetHeight = required.height

        // Soft aspect guard: expand the short dimension only. Never shrink around the athletes.
        var aspect = targetWidth / targetHeight.coerceAtLeast(1.0)
        if (aspect < MIN_ASPECT) {
            targetWidth = targetHeight * MIN_ASPECT
        } else if (aspect > MAX_ASPECT) {
            targetHeight = targetWidth / MAX_ASPECT
        }

        // Extremely small crops are visually fragile and amplify any detector error. Expand gently.
        val imageArea = image.width.toDouble() * image.height.toDouble()
        val minAreaFraction = if (clean.size >= 2) MIN_PAIR_AREA_FRACTION else MIN_SINGLE_AREA_FRACTION
        val currentArea = targetWidth * targetHeight
        val wantedArea = imageArea * minAreaFraction
        if (currentArea < wantedArea && currentArea > 1.0) {
            val scale = sqrt(wantedArea / currentArea)
            targetWidth *= scale
            targetHeight *= scale
        }

        targetWidth = targetWidth.coerceIn(required.width, image.width.toDouble())
        targetHeight = targetHeight.coerceIn(required.height, image.height.toDouble())

        // Re-check aspect after image-bound clamping. Expansion is still the only correction.
        aspect = targetWidth / targetHeight.coerceAtLeast(1.0)
        if (aspect < MIN_ASPECT && targetWidth < image.width) {
            targetWidth = min(image.width.toDouble(), targetHeight * MIN_ASPECT)
        } else if (aspect > MAX_ASPECT && targetHeight < image.height) {
            targetHeight = min(image.height.toDouble(), targetWidth / MAX_ASPECT)
        }

        val verticalBias = if (ratio <= 0.82) -0.015 else 0.0
        return placeFrame(image, required, subject, targetWidth, targetHeight, verticalBias)
            ?: full(image)
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

    private fun placeFrame(
        image: ImageSize,
        required: RectD,
        subject: RectD,
        requestedWidth: Double,
        requestedHeight: Double,
        verticalBias: Double,
    ): PixelRect? {
        val width = requestedWidth.coerceIn(required.width, image.width.toDouble())
        val height = requestedHeight.coerceIn(required.height, image.height.toDouble())
        if (width < 2.0 || height < 2.0) return null

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

        val px = PixelRect(
            left = floor(left).toInt().coerceIn(0, image.width - 1),
            top = floor(top).toInt().coerceIn(0, image.height - 1),
            right = ceil(left + width).toInt().coerceIn(1, image.width),
            bottom = ceil(top + height).toInt().coerceIn(1, image.height),
        )
        return px.takeIf { it.width >= 2 && it.height >= 2 }
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
