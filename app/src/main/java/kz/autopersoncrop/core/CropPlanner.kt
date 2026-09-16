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
 * Subject-first crop planner.
 *
 * Rules:
 * - a standing/sitting portrait subject keeps about 10% breathing room above and below;
 * - a lying/wide subject keeps about 10% breathing room on the left and right;
 * - the remaining sides are cropped to the nearest natural photographic aspect;
 * - the source pixels are only cropped: no canvas, padding, stretching or black bars are created;
 * - tiny/distant subjects are NOT intentionally kept tiny: the crop is driven by the subject bounds;
 * - secondary body fragments touching an image edge do not drag the crop away from a complete main subject.
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
        val layout = chooseLayout(image, subject, selected.size)
        val preferredMargin = marginFraction.coerceIn(0.0, 0.20)

        // Keep the requested 10% whenever the source contains enough pixels around the subject.
        // If a person already touches an original edge, relax only that unavailable margin.
        val required = compositionBounds(subject, image, layout, preferredMargin)
        chooseBestCrop(image, required, subject, layout)?.let { best ->
            return planFromPlaced(
                placed = best.placed,
                layout = layout,
                aspect = best.aspect,
                status = best.status,
                subject = subject,
                required = required,
                image = image,
            )
        }

        // Try smaller main-axis margins only when 10% physically cannot fit a valid crop.
        for (margin in listOf(0.08, 0.06, 0.04, 0.02, 0.0)) {
            if (margin >= preferredMargin) continue
            val relaxed = compositionBounds(subject, image, layout, margin)
            chooseBestCrop(image, relaxed, subject, layout)?.let { best ->
                return planFromPlaced(
                    placed = best.placed,
                    layout = layout,
                    aspect = best.aspect,
                    status = CropStatus.ADAPTIVE_MARGIN,
                    subject = subject,
                    required = relaxed,
                    image = image,
                )
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

    private data class CropChoice(
        val placed: Placed,
        val aspect: Double,
        val status: CropStatus,
        val score: Double,
    )

    /**
     * Choose the natural photo ratio that needs the least extra scene around the safe subject box.
     * This prevents the old behaviour where a distant person stayed very small in a large crop.
     */
    private fun chooseBestCrop(
        image: ImageSize,
        required: RectD,
        subject: RectD,
        layout: SubjectLayout,
    ): CropChoice? {
        val aspects = when (layout) {
            SubjectLayout.PORTRAIT -> listOf(2.0 / 3.0, 3.0 / 4.0, 4.0 / 5.0)
            SubjectLayout.LANDSCAPE -> listOf(4.0 / 3.0, 3.0 / 2.0, 16.0 / 9.0)
        }

        var best: CropChoice? = null
        for (aspect in aspects) {
            val fitted = fitAspectInside(image, required, aspect) ?: continue
            val placed = placeAroundFocus(
                required = required,
                focusX = subject.centerX,
                focusY = subject.centerY,
                image = image,
                cropW = fitted.first,
                cropH = fitted.second,
            ) ?: continue
            val cropArea = (placed.right - placed.left) * (placed.bottom - placed.top)
            val extraAreaRatio = cropArea / required.area.coerceAtLeast(1.0)
            val score = extraAreaRatio + if (placed.shifted) 0.025 else 0.0
            val choice = CropChoice(
                placed = placed,
                aspect = aspect,
                status = if (placed.shifted) CropStatus.SHIFTED_TO_IMAGE_EDGE else CropStatus.EXACT,
                score = score,
            )
            if (best == null || choice.score < best!!.score) best = choice
        }
        if (best != null) return best

        // A standard ratio may be impossible near an original image edge. In that case use the
        // tightest feasible ratio without ever cutting the required safe subject area.
        val minFeasible = required.width / image.height.toDouble()
        val maxFeasible = image.width.toDouble() / required.height.coerceAtLeast(1.0)
        if (minFeasible > maxFeasible + EPS) return null
        val natural = required.width / required.height.coerceAtLeast(1.0)
        val aspect = natural.coerceIn(minFeasible, maxFeasible)
        val fitted = fitAspectInside(image, required, aspect) ?: return null
        val placed = placeAroundFocus(
            required = required,
            focusX = subject.centerX,
            focusY = subject.centerY,
            image = image,
            cropW = fitted.first,
            cropH = fitted.second,
        ) ?: return null
        return CropChoice(placed, aspect, CropStatus.ADAPTIVE_ASPECT, 0.0)
    }

    private fun chooseLayout(image: ImageSize, subject: RectD, subjectCount: Int): SubjectLayout {
        val ratio = subject.width / subject.height.coerceAtLeast(1.0)
        val sourceLayout = if (image.height >= image.width) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE

        return if (subjectCount == 1) {
            when {
                // Typical standing or sitting person.
                ratio <= 0.92 -> SubjectLayout.PORTRAIT
                // Lying person or clearly horizontal pose.
                ratio >= 1.15 -> SubjectLayout.LANDSCAPE
                else -> sourceLayout
            }
        } else {
            when {
                // A narrow vertical group.
                ratio <= 0.78 -> SubjectLayout.PORTRAIT
                // Side-by-side group is normally better as landscape.
                ratio >= 0.95 -> SubjectLayout.LANDSCAPE
                else -> sourceLayout
            }
        }
    }

    /**
     * Portrait subject: 10% above + 10% below, with only a small horizontal safety allowance.
     * Landscape/lying subject: 10% left + 10% right, with only a small vertical safety allowance.
     */
    private fun compositionBounds(
        subject: RectD,
        image: ImageSize,
        layout: SubjectLayout,
        mainMargin: Double,
    ): RectD {
        val crossMargin = 0.06
        return when (layout) {
            SubjectLayout.PORTRAIT -> RectD(
                left = subject.left - subject.width * crossMargin,
                top = subject.top - subject.height * mainMargin,
                right = subject.right + subject.width * crossMargin,
                bottom = subject.bottom + subject.height * mainMargin,
            ).clampTo(image)
            SubjectLayout.LANDSCAPE -> RectD(
                left = subject.left - subject.width * mainMargin,
                top = subject.top - subject.height * crossMargin,
                right = subject.right + subject.width * mainMargin,
                bottom = subject.bottom + subject.height * crossMargin,
            ).clampTo(image)
        }
    }

    /**
     * Pick a complete, central principal person first, then keep plausible co-subjects.
     */
    private fun selectPrincipalSubjects(image: ImageSize, input: List<RectD>): List<RectD> {
        val valid = input
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (valid.size <= 1) return valid.ifEmpty { listOf(input.first().clampTo(image)) }

        val imageArea = image.width.toDouble() * image.height.toDouble()
        val maxArea = valid.maxOf { it.area }.coerceAtLeast(1.0)
        val imageCx = image.width / 2.0
        val imageCy = image.height / 2.0
        val halfDiag = sqrt(imageCx * imageCx + imageCy * imageCy).coerceAtLeast(1.0)

        fun centrality(r: RectD): Double {
            val dx = r.centerX - imageCx
            val dy = r.centerY - imageCy
            return (1.0 - sqrt(dx * dx + dy * dy) / halfDiag).coerceIn(0.0, 1.0)
        }

        fun anchorScore(r: RectD): Double {
            val edge = edgeContact(r, image)
            val size = (r.area / maxArea).coerceIn(0.0, 1.0)
            val completeness = when {
                edge.none -> 1.0
                edge.bottomOnly -> 0.82
                edge.count == 1 -> 0.42
                else -> 0.22
            }
            val severePenalty = when {
                edge.none -> 0.0
                edge.bottomOnly -> 0.05
                edge.count == 1 -> 0.18
                else -> 0.28
            }
            return size * 0.55 + centrality(r) * 0.30 + completeness * 0.15 - severePenalty
        }

        val anchor = valid.maxByOrNull(::anchorScore) ?: valid.first()
        val anchorArea = anchor.area.coerceAtLeast(1.0)
        val anchorEdge = edgeContact(anchor, image)

        val selected = valid.filter { r ->
            if (r == anchor) return@filter true

            val edge = edgeContact(r, image)
            val areaRatio = r.area / anchorArea
            val heightRatio = r.height / anchor.height.coerceAtLeast(1.0)
            val imageRatio = r.area / imageArea
            val dx = (r.centerX - anchor.centerX) / image.width.toDouble()
            val dy = (r.centerY - anchor.centerY) / image.height.toDouble()
            val distance = sqrt(dx * dx + dy * dy)
            val gap = normalizedGap(anchor, r, image)
            val nearby = distance <= 0.55 || gap <= 0.10
            if (!nearby) return@filter false

            when {
                edge.none -> {
                    (heightRatio >= 0.34 && areaRatio >= 0.10) ||
                        (imageRatio >= 0.014 && heightRatio >= 0.30)
                }
                edge.bottomOnly -> {
                    heightRatio >= 0.45 && areaRatio >= 0.18
                }
                !anchorEdge.none -> {
                    heightRatio >= 0.68 && areaRatio >= 0.48 && distance <= 0.44
                }
                else -> {
                    // Ignore a random body fragment on an edge unless it is obviously part of the
                    // same foreground group and almost the same scale as the principal subject.
                    heightRatio >= 0.82 && areaRatio >= 0.70 && edge.count == 1 && distance <= 0.42
                }
            }
        }

        return if (selected.isEmpty()) listOf(anchor) else selected
    }

    private data class EdgeContact(
        val left: Boolean,
        val top: Boolean,
        val right: Boolean,
        val bottom: Boolean,
    ) {
        val count: Int get() = listOf(left, top, right, bottom).count { it }
        val none: Boolean get() = count == 0
        val bottomOnly: Boolean get() = bottom && !left && !top && !right
    }

    private fun edgeContact(r: RectD, image: ImageSize): EdgeContact {
        val edgeX = max(3.0, image.width * 0.012)
        val edgeY = max(3.0, image.height * 0.012)
        return EdgeContact(
            left = r.left <= edgeX,
            top = r.top <= edgeY,
            right = r.right >= image.width - edgeX,
            bottom = r.bottom >= image.height - edgeY,
        )
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

    private fun placeAroundFocus(
        required: RectD,
        focusX: Double,
        focusY: Double,
        image: ImageSize,
        cropW: Double,
        cropH: Double,
    ): Placed? {
        var left = focusX - cropW / 2.0
        var top = focusY - cropH / 2.0
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

        if (left > required.left) {
            val d = left - required.left
            left -= d
            right -= d
            shifted = true
        }
        if (right < required.right) {
            val d = required.right - right
            left += d
            right += d
            shifted = true
        }
        if (top > required.top) {
            val d = top - required.top
            top -= d
            bottom -= d
            shifted = true
        }
        if (bottom < required.bottom) {
            val d = required.bottom - bottom
            top += d
            bottom += d
            shifted = true
        }

        if (left < -EPS || top < -EPS || right > image.width + EPS || bottom > image.height + EPS) return null
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
