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
 * Composition-aware crop planner.
 *
 * The detector can return every visible person, including a stray arm/torso at an image edge.
 * We first select the principal subject/group, then frame that subject. A secondary edge-clipped
 * person must not force the main person away from the visual centre.
 *
 * Framing priority:
 * 1) keep the principal subject/group intact;
 * 2) centre it as much as the source image permits;
 * 3) try the preferred 5% breathing room on the major axis;
 * 4) reduce that margin smoothly when necessary;
 * 5) if the requested phone aspect physically cannot contain the subject, relax the aspect ratio
 *    instead of falling straight back to the whole source photo.
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

        val selected = selectPrincipalSubjects(image, people)
        val subject = union(selected).clampTo(image)
        val layout = chooseLayout(subject)

        val shortSide = min(screenWidth, screenHeight).toDouble()
        val longSide = max(screenWidth, screenHeight).toDouble()
        val requestedAspect = when (layout) {
            SubjectLayout.PORTRAIT -> shortSide / longSide
            SubjectLayout.LANDSCAPE -> longSide / shortSide
        }

        val steps = marginSteps(marginFraction)
        for ((index, margin) in steps.withIndex()) {
            val required = paddedMajorAxis(subject, image, layout, margin)
            val fitted = fitAspectInside(image, required, requestedAspect)
            if (fitted != null) {
                val placed = placeAround(required, image, fitted.first, fitted.second)
                if (placed != null) {
                    val status = when {
                        index > 0 -> CropStatus.ADAPTIVE_MARGIN
                        placed.shifted -> CropStatus.SHIFTED_TO_IMAGE_EDGE
                        else -> CropStatus.EXACT
                    }
                    return planFromPlaced(
                        placed = placed,
                        layout = layout,
                        aspect = requestedAspect,
                        status = status,
                        subject = subject,
                        required = required,
                        image = image,
                    )
                }
            }
        }

        // The phone aspect is too narrow/wide for this subject. Find the nearest feasible aspect
        // that still keeps the principal subject intact and centre it within the source.
        val minFeasibleAspect = subject.width / image.height.toDouble()
        val maxFeasibleAspect = image.width.toDouble() / subject.height
        if (minFeasibleAspect <= maxFeasibleAspect + EPS) {
            val adaptiveAspect = requestedAspect.coerceIn(minFeasibleAspect, maxFeasibleAspect)
            val required = subject
            val fitted = fitAspectInside(image, required, adaptiveAspect)
            if (fitted != null) {
                val placed = placeAround(required, image, fitted.first, fitted.second)
                if (placed != null) {
                    return planFromPlaced(
                        placed = placed,
                        layout = layout,
                        aspect = adaptiveAspect,
                        status = CropStatus.ADAPTIVE_ASPECT,
                        subject = subject,
                        required = required,
                        image = image,
                    )
                }
            }
        }

        return CropPlan(
            rect = PixelRect(0, 0, image.width, image.height),
            layout = layout,
            targetAspect = image.width.toDouble() / image.height.toDouble(),
            status = CropStatus.SAFE_FALLBACK_FULL_IMAGE,
            subjectBounds = subject,
            requiredBounds = subject,
        )
    }

    private fun chooseLayout(subject: RectD): SubjectLayout {
        // A small dead-band avoids flipping orientation for nearly square groups.
        return when {
            subject.height >= subject.width * 1.08 -> SubjectLayout.PORTRAIT
            subject.width >= subject.height * 1.08 -> SubjectLayout.LANDSCAPE
            else -> if (subject.height >= subject.width) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
        }
    }

    /**
     * Pick the main person first, then retain only meaningful companions.
     * A box touching the source edge is likely a partial body. Such a secondary box is ignored
     * unless it is large enough to plausibly be a co-subject.
     */
    private fun selectPrincipalSubjects(image: ImageSize, input: List<RectD>): List<RectD> {
        val valid = input
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (valid.size <= 1) return valid.ifEmpty { listOf(input.first().clampTo(image)) }

        val imageArea = image.width.toDouble() * image.height.toDouble()
        val maxArea = valid.maxOf { it.area }.coerceAtLeast(1.0)
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val halfDiag = sqrt(cx * cx + cy * cy).coerceAtLeast(1.0)

        fun centrality(r: RectD): Double {
            val dx = r.centerX - cx
            val dy = r.centerY - cy
            return (1.0 - sqrt(dx * dx + dy * dy) / halfDiag).coerceIn(0.0, 1.0)
        }

        fun score(r: RectD): Double {
            val sizeScore = (r.area / maxArea).coerceIn(0.0, 1.0)
            val edgePenalty = if (touchesImageEdge(r, image)) 0.22 else 0.0
            return sizeScore * 0.72 + centrality(r) * 0.28 - edgePenalty
        }

        val anchor = valid.maxByOrNull(::score) ?: valid.first()
        val anchorArea = anchor.area.coerceAtLeast(1.0)
        val anchorTouchesEdge = touchesImageEdge(anchor, image)

        val selected = valid.filter { r ->
            if (r === anchor || r == anchor) return@filter true

            val areaRatio = r.area / anchorArea
            val imageRatio = r.area / imageArea
            val dx = (r.centerX - anchor.centerX) / image.width.toDouble()
            val dy = (r.centerY - anchor.centerY) / image.height.toDouble()
            val relativeDistance = sqrt(dx * dx + dy * dy)

            val meaningfulSize = areaRatio >= 0.20 || imageRatio >= 0.012
            if (!meaningfulSize || relativeDistance > 0.62) return@filter false

            val edgeClipped = touchesImageEdge(r, image)
            if (edgeClipped && !anchorTouchesEdge && areaRatio < 0.72) return@filter false
            if (edgeClipped && areaRatio < 0.45) return@filter false

            true
        }

        return if (selected.isEmpty()) listOf(anchor) else selected
    }

    private fun touchesImageEdge(r: RectD, image: ImageSize): Boolean {
        val edgeX = max(3.0, image.width * 0.015)
        val edgeY = max(3.0, image.height * 0.015)
        return r.left <= edgeX || r.top <= edgeY ||
            r.right >= image.width - edgeX || r.bottom >= image.height - edgeY
    }

    private fun marginSteps(preferred: Double): List<Double> {
        if (preferred <= 0.0) return listOf(0.0)
        val result = ArrayList<Double>()
        var value = preferred
        val step = max(0.01, preferred / 5.0)
        while (value > 0.0001) {
            result += value
            value -= step
        }
        result += 0.0
        return result.distinctBy { (it * 10000).toInt() }
    }

    private fun paddedMajorAxis(
        subject: RectD,
        image: ImageSize,
        layout: SubjectLayout,
        margin: Double,
    ): RectD = when (layout) {
        SubjectLayout.PORTRAIT -> {
            val pad = subject.height * margin
            RectD(subject.left, subject.top - pad, subject.right, subject.bottom + pad).clampTo(image)
        }
        SubjectLayout.LANDSCAPE -> {
            val pad = subject.width * margin
            RectD(subject.left - pad, subject.top, subject.right + pad, subject.bottom).clampTo(image)
        }
    }

    private fun fitAspectInside(image: ImageSize, required: RectD, aspect: Double): Pair<Double, Double>? {
        if (aspect <= 0.0) return null
        val cropH = max(required.height, required.width / aspect)
        val cropW = cropH * aspect
        if (cropW > image.width + EPS || cropH > image.height + EPS) return null
        return cropW to cropH
    }

    private data class Placed(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val shifted: Boolean,
    )

    private fun placeAround(
        required: RectD,
        image: ImageSize,
        cropW: Double,
        cropH: Double,
    ): Placed? {
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

        if (left > required.left + EPS || top > required.top + EPS ||
            right < required.right - EPS || bottom < required.bottom - EPS
        ) return null

        return Placed(left, top, right, bottom, shifted)
    }

    private fun planFromPlaced(
        placed: Placed,
        layout: SubjectLayout,
        aspect: Double,
        status: CropStatus,
        subject: RectD,
        required: RectD,
        image: ImageSize,
    ): CropPlan {
        val pixel = PixelRect(
            left = floor(placed.left).toInt().coerceIn(0, image.width - 1),
            top = floor(placed.top).toInt().coerceIn(0, image.height - 1),
            right = ceil(placed.right).toInt().coerceIn(1, image.width),
            bottom = ceil(placed.bottom).toInt().coerceIn(1, image.height),
        )
        return CropPlan(
            rect = pixel,
            layout = layout,
            targetAspect = aspect,
            status = status,
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

    private const val EPS = 1e-6
}
