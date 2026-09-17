package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ImageSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
    val area: Double get() = width * height
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class SubjectLayout { PORTRAIT, LANDSCAPE }

enum class CropStatus {
    EXACT,
    SHIFTED_TO_IMAGE_EDGE,
    ADAPTIVE_MARGIN,
    ADAPTIVE_ASPECT,
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
 * AutoPersonCrop 0.5.1 composition-first planner.
 *
 * The detector boxes represent the visible body area. We never invent a canvas and never force a
 * phone-screen aspect ratio. The selected person/group is kept centred by cropping only source
 * edges. If a body is already cut by the original frame, the visible body box itself becomes the
 * centring reference; unavailable margin is not recreated with padding.
 */
object CropPlanner {
    @Suppress("UNUSED_PARAMETER")
    fun plan(
        image: ImageSize,
        people: List<RectD>,
        screenWidth: Int,
        screenHeight: Int,
        marginFraction: Double = 0.10,
    ): CropPlan {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val selected = selectPrincipalSubjects(image, people)
        val subject = union(selected).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return fullImage(image, subject)

        val margin = marginFraction.coerceIn(0.0, 0.20)
        val desiredX = max(subject.width * margin, subject.height * 0.035)
        val desiredY = max(subject.height * margin, subject.width * 0.035)

        // Symmetric margin is deliberately limited by the smaller amount of real source pixels
        // available on opposite sides. That keeps the visible person/group in the centre even when
        // the original photo already cuts off a head, leg, arm or side of the body.
        val marginX = min(desiredX, min(subject.left, image.width - subject.right).coerceAtLeast(0.0))
        val marginY = min(desiredY, min(subject.top, image.height - subject.bottom).coerceAtLeast(0.0))

        val required = RectD(
            subject.left - marginX,
            subject.top - marginY,
            subject.right + marginX,
            subject.bottom + marginY,
        ).clampTo(image)

        val rect = PixelRect(
            floor(required.left).toInt().coerceIn(0, image.width - 1),
            floor(required.top).toInt().coerceIn(0, image.height - 1),
            ceil(required.right).toInt().coerceIn(1, image.width),
            ceil(required.bottom).toInt().coerceIn(1, image.height),
        )

        if (rect.width <= 1 || rect.height <= 1) return fullImage(image, subject)

        val layout = if (subject.width > subject.height * 1.05) SubjectLayout.LANDSCAPE else SubjectLayout.PORTRAIT
        val exactMargins = marginX + 0.5 >= desiredX && marginY + 0.5 >= desiredY
        return CropPlan(
            rect = rect,
            layout = layout,
            targetAspect = rect.width.toDouble() / rect.height.toDouble(),
            status = if (exactMargins) CropStatus.EXACT else CropStatus.ADAPTIVE_MARGIN,
            subjectBounds = subject,
            requiredBounds = required,
        )
    }

    /**
     * Selection rules:
     * 1) Prefer complete main people over body fragments touching a source edge.
     * 2) Keep two or more similarly scaled complete people when they form one group.
     * 3) If everybody is partial, use the largest/most central visible body area and include only
     *    nearby bodies of comparable scale.
     */
    private fun selectPrincipalSubjects(image: ImageSize, input: List<RectD>): List<RectD> {
        val valid = input
            .map { it.clampTo(image) }
            .filter { it.width >= 3.0 && it.height >= 3.0 }
        if (valid.size <= 1) return valid.ifEmpty { listOf(input.first().clampTo(image)) }

        val maxArea = valid.maxOf { it.area }.coerceAtLeast(1.0)
        val imageCx = image.width / 2.0
        val imageCy = image.height / 2.0
        val halfDiag = sqrt(imageCx * imageCx + imageCy * imageCy).coerceAtLeast(1.0)

        fun centrality(r: RectD): Double {
            val dx = r.centerX - imageCx
            val dy = r.centerY - imageCy
            return (1.0 - sqrt(dx * dx + dy * dy) / halfDiag).coerceIn(0.0, 1.0)
        }

        fun score(r: RectD): Double =
            (r.area / maxArea).coerceIn(0.0, 1.0) * 0.72 + centrality(r) * 0.28

        val complete = valid.filter { !isSideOrTopFragment(it, image) }
        if (complete.isNotEmpty()) {
            val anchor = complete.maxByOrNull(::score) ?: complete.first()
            val selected = complete.filter { candidate ->
                if (candidate == anchor) return@filter true
                val heightRatio = candidate.height / anchor.height.coerceAtLeast(1.0)
                val areaRatio = candidate.area / anchor.area.coerceAtLeast(1.0)
                val near = normalizedGap(anchor, candidate, image) <= 0.16 ||
                    normalizedCenterDistance(anchor, candidate, image) <= 0.58
                near && heightRatio >= 0.34 && areaRatio >= 0.10
            }
            return selected.ifEmpty { listOf(anchor) }
        }

        // No complete person exists: centre by the largest visible body part instead of trying to
        // reconstruct missing anatomy beyond the original image boundary.
        val anchor = valid.maxByOrNull(::score) ?: valid.first()
        val selected = valid.filter { candidate ->
            if (candidate == anchor) return@filter true
            val areaRatio = candidate.area / anchor.area.coerceAtLeast(1.0)
            val heightRatio = candidate.height / anchor.height.coerceAtLeast(1.0)
            val near = normalizedGap(anchor, candidate, image) <= 0.12 ||
                normalizedCenterDistance(anchor, candidate, image) <= 0.48
            near && areaRatio >= 0.28 && heightRatio >= 0.45
        }
        return selected.ifEmpty { listOf(anchor) }
    }

    /** A second person clipped at left/right/top must not drag a complete main subject's crop. */
    private fun isSideOrTopFragment(r: RectD, image: ImageSize): Boolean {
        val edgeX = max(3.0, image.width * 0.010)
        val edgeY = max(3.0, image.height * 0.010)
        return r.left <= edgeX || r.right >= image.width - edgeX || r.top <= edgeY
    }

    private fun normalizedCenterDistance(a: RectD, b: RectD, image: ImageSize): Double {
        val dx = (a.centerX - b.centerX) / image.width.toDouble()
        val dy = (a.centerY - b.centerY) / image.height.toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun normalizedGap(a: RectD, b: RectD, image: ImageSize): Double {
        val horizontal = when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0.0
        } / image.width.toDouble()
        val vertical = when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0.0
        } / image.height.toDouble()
        return sqrt(horizontal * horizontal + vertical * vertical)
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

    private fun fullImage(image: ImageSize, subject: RectD) = CropPlan(
        rect = PixelRect(0, 0, image.width, image.height),
        layout = if (image.width > image.height) SubjectLayout.LANDSCAPE else SubjectLayout.PORTRAIT,
        targetAspect = image.width.toDouble() / image.height.toDouble(),
        status = CropStatus.SAFE_FALLBACK_FULL_IMAGE,
        subjectBounds = subject,
        requiredBounds = subject,
    )
}
