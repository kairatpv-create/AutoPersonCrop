package kz.autopersoncrop.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor

data class ImageSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class SubjectLayout { PORTRAIT, LANDSCAPE }

enum class CropStatus {
    EXACT,
    SHIFTED_TO_IMAGE_EDGE,
    SAFE_FALLBACK_FULL_IMAGE
}

data class CropPlan(
    val rect: PixelRect,
    val layout: SubjectLayout,
    val targetAspect: Double,
    val status: CropStatus,
    val subjectBounds: RectD,
    val requiredBounds: RectD,
)

/**
 * Smart crop planner.
 *
 * Priority order:
 * 1) never cut a detected person;
 * 2) keep the requested phone/screen aspect ratio;
 * 3) keep a 5% composition margin on the major axis:
 *    portrait -> above head and below feet;
 *    landscape -> left and right of subject/group.
 *
 * No image scaling or stretching is ever requested by this class.
 */
object CropPlanner {
    fun plan(
        image: ImageSize,
        people: List<RectD>,
        screenWidth: Int,
        screenHeight: Int,
        marginFraction: Double = 0.05,
    ): CropPlan {
        require(people.isNotEmpty()) { "At least one person box is required" }
        require(screenWidth > 0 && screenHeight > 0)
        require(marginFraction >= 0.0)

        val subject = union(people).clampTo(image)
        val layout = if (subject.height >= subject.width) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE

        val shortSide = min(screenWidth, screenHeight).toDouble()
        val longSide = max(screenWidth, screenHeight).toDouble()
        val targetAspect = when (layout) {
            SubjectLayout.PORTRAIT -> shortSide / longSide // W/H
            SubjectLayout.LANDSCAPE -> longSide / shortSide // W/H
        }

        val required = when (layout) {
            SubjectLayout.PORTRAIT -> {
                val pad = subject.height * marginFraction
                RectD(subject.left, subject.top - pad, subject.right, subject.bottom + pad).clampTo(image)
            }
            SubjectLayout.LANDSCAPE -> {
                val pad = subject.width * marginFraction
                RectD(subject.left - pad, subject.top, subject.right + pad, subject.bottom).clampTo(image)
            }
        }

        // Smallest fixed-aspect rectangle that contains the required safe bounds.
        var cropH = max(required.height, required.width / targetAspect)
        var cropW = cropH * targetAspect

        // If the requested aspect cannot fit inside the source while still containing every person,
        // do not sacrifice a person or distort the photo. Preserve the whole original as a safe fallback.
        if (cropW > image.width + 1e-6 || cropH > image.height + 1e-6) {
            return CropPlan(
                rect = PixelRect(0, 0, image.width, image.height),
                layout = layout,
                targetAspect = targetAspect,
                status = CropStatus.SAFE_FALLBACK_FULL_IMAGE,
                subjectBounds = subject,
                requiredBounds = required,
            )
        }

        // Center on all detected people, then shift (do not shrink) if an image edge is reached.
        var left = required.centerX - cropW / 2.0
        var top = required.centerY - cropH / 2.0
        var right = left + cropW
        var bottom = top + cropH
        var shifted = false

        if (left < 0.0) {
            right -= left
            left = 0.0
            shifted = true
        }
        if (right > image.width) {
            val d = right - image.width
            left -= d
            right = image.width.toDouble()
            shifted = true
        }
        if (top < 0.0) {
            bottom -= top
            top = 0.0
            shifted = true
        }
        if (bottom > image.height) {
            val d = bottom - image.height
            top -= d
            bottom = image.height.toDouble()
            shifted = true
        }

        // Numerical guard. If shifting unexpectedly made the safe bounds impossible, preserve full image.
        if (left > required.left + 1e-6 || top > required.top + 1e-6 ||
            right < required.right - 1e-6 || bottom < required.bottom - 1e-6
        ) {
            return CropPlan(
                rect = PixelRect(0, 0, image.width, image.height),
                layout = layout,
                targetAspect = targetAspect,
                status = CropStatus.SAFE_FALLBACK_FULL_IMAGE,
                subjectBounds = subject,
                requiredBounds = required,
            )
        }

        // Round OUTWARD so integer conversion can never trim even one pixel from the safe area.
        val pixel = PixelRect(
            left = floor(left).toInt().coerceIn(0, image.width - 1),
            top = floor(top).toInt().coerceIn(0, image.height - 1),
            right = ceil(right).toInt().coerceIn(1, image.width),
            bottom = ceil(bottom).toInt().coerceIn(1, image.height),
        )

        return CropPlan(
            rect = pixel,
            layout = layout,
            targetAspect = targetAspect,
            status = if (shifted) CropStatus.SHIFTED_TO_IMAGE_EDGE else CropStatus.EXACT,
            subjectBounds = subject,
            requiredBounds = required,
        )
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom },
    )

    private fun RectD.clampTo(image: ImageSize): RectD = RectD(
        left = left.coerceIn(0.0, image.width.toDouble()),
        top = top.coerceIn(0.0, image.height.toDouble()),
        right = right.coerceIn(0.0, image.width.toDouble()),
        bottom = bottom.coerceIn(0.0, image.height.toDouble()),
    )
}
