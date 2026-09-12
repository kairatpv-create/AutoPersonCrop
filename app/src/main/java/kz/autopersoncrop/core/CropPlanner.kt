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
 * Composition-first crop planner.
 *
 * Important: the phone screen ratio is intentionally NOT used as the photo ratio. Modern phones
 * are very tall (roughly 9:20), which produced unnaturally narrow portraits and excessively wide
 * landscapes. We choose normal photographic ratios instead and relax them only when the source or
 * the subject requires it.
 *
 * Priority:
 * 1) choose the principal complete person/group;
 * 2) ignore a secondary body fragment touching an image edge when a complete principal subject exists;
 * 3) keep the principal subject/group intact and as close to the visual centre as possible;
 * 4) prefer about 5% breathing room on the main axis and some context on the cross axis;
 * 5) use photographic ratios (2:3 / 3:4 / 4:5, or 4:3 / 3:2 / 16:9), not the device screen;
 * 6) avoid extreme zoom when the detected people are small in the original scene.
 */
object CropPlanner {
    @Suppress("UNUSED_PARAMETER")
    fun plan(
        image: ImageSize,
        people: List<RectD>,
        screenWidth: Int,
        screenHeight: Int,
        marginFraction: Double = 0.05,
    ): CropPlan {
        require(people.isNotEmpty()) { "At least one person box is required" }
        require(marginFraction >= 0.0)

        val selected = selectPrincipalSubjects(image, people)
        val subject = union(selected).clampTo(image)
        val layout = chooseLayout(image, subject, selected.size)
        val preferredAspect = preferredPhotoAspect(subject, layout)

        val steps = marginSteps(marginFraction)
        for ((index, margin) in steps.withIndex()) {
            val required = compositionBounds(subject, image, layout, margin)
            val aspect = chooseFeasibleAspect(image, required, preferredAspect, layout) ?: continue
            val fitted = fitAspectInside(image, required, aspect) ?: continue
            val expanded = expandForSceneContext(
                image = image,
                subject = subject,
                cropW = fitted.first,
                cropH = fitted.second,
            )
            val placed = placeAroundFocus(
                required = required,
                focusX = subject.centerX,
                focusY = subject.centerY,
                image = image,
                cropW = expanded.first,
                cropH = expanded.second,
            ) ?: continue

            val status = when {
                kotlin.math.abs(aspect - preferredAspect) > 0.025 -> CropStatus.ADAPTIVE_ASPECT
                index > 0 -> CropStatus.ADAPTIVE_MARGIN
                placed.shifted -> CropStatus.SHIFTED_TO_IMAGE_EDGE
                else -> CropStatus.EXACT
            }
            return planFromPlaced(
                placed = placed,
                layout = layout,
                aspect = aspect,
                status = status,
                subject = subject,
                required = required,
                image = image,
            )
        }

        // Last resort: keep the selected principal subject and use the nearest feasible ratio.
        val required = subject
        val fallbackAspect = chooseFeasibleAspect(image, required, preferredAspect, layout)
        if (fallbackAspect != null) {
            val fitted = fitAspectInside(image, required, fallbackAspect)
            if (fitted != null) {
                val placed = placeAroundFocus(
                    required = required,
                    focusX = subject.centerX,
                    focusY = subject.centerY,
                    image = image,
                    cropW = fitted.first,
                    cropH = fitted.second,
                )
                if (placed != null) {
                    return planFromPlaced(
                        placed = placed,
                        layout = layout,
                        aspect = fallbackAspect,
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

    private fun chooseLayout(image: ImageSize, subject: RectD, subjectCount: Int): SubjectLayout {
        val ratio = subject.width / subject.height.coerceAtLeast(1.0)
        return when {
            // One clearly standing person should remain a portrait even in a landscape source.
            subjectCount == 1 && ratio <= 0.86 -> SubjectLayout.PORTRAIT
            // One clearly lying/wide person should remain landscape even in a portrait source.
            subjectCount == 1 && ratio >= 1.18 -> SubjectLayout.LANDSCAPE
            // Groups are classified by their combined geometry with a dead-band near square.
            ratio <= 0.90 -> SubjectLayout.PORTRAIT
            ratio >= 1.10 -> SubjectLayout.LANDSCAPE
            // Near-square groups inherit the source orientation instead of flipping unpredictably.
            image.height >= image.width -> SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
        }
    }

    private fun preferredPhotoAspect(subject: RectD, layout: SubjectLayout): Double {
        val ratio = subject.width / subject.height.coerceAtLeast(1.0)
        return when (layout) {
            SubjectLayout.PORTRAIT -> when {
                ratio <= 0.42 -> 2.0 / 3.0
                ratio <= 0.62 -> 3.0 / 4.0
                else -> 4.0 / 5.0
            }
            SubjectLayout.LANDSCAPE -> when {
                ratio >= 2.15 -> 16.0 / 9.0
                ratio >= 1.55 -> 3.0 / 2.0
                else -> 4.0 / 3.0
            }
        }
    }

    /**
     * Pick a complete, central principal person first, then keep only plausible co-subjects.
     * Side/top edge contact is treated more aggressively than bottom-only contact: a person can
     * naturally stand on the bottom edge, while a box cut by the left/right/top edge is commonly a
     * body fragment that should not drag the crop away from the main subject.
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
            val nearby = distance <= 0.52 || gap <= 0.08
            if (!nearby) return@filter false

            when {
                edge.none -> {
                    // A complete companion may be smaller because of perspective, but not a tiny
                    // background pedestrian.
                    (heightRatio >= 0.38 && areaRatio >= 0.12) ||
                        (imageRatio >= 0.018 && heightRatio >= 0.32)
                }
                edge.bottomOnly -> {
                    // Feet close to the bottom edge are common and can still be a full co-subject.
                    heightRatio >= 0.48 && areaRatio >= 0.20
                }
                !anchorEdge.none -> {
                    // If the whole scene itself is edge-constrained, keep a similarly large partner.
                    heightRatio >= 0.72 && areaRatio >= 0.55 && distance <= 0.42
                }
                else -> {
                    // Main requested case: complete central person + partial second body at an edge.
                    // Ignore the fragment unless it is almost the same scale as the main subject.
                    heightRatio >= 0.88 && areaRatio >= 0.82 && edge.count == 1 && distance <= 0.38
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

    private fun compositionBounds(
        subject: RectD,
        image: ImageSize,
        layout: SubjectLayout,
        mainMargin: Double,
    ): RectD {
        val crossMargin = min(0.06, max(0.025, mainMargin * 0.8))
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

    private fun chooseFeasibleAspect(
        image: ImageSize,
        required: RectD,
        preferred: Double,
        layout: SubjectLayout,
    ): Double? {
        val minFeasible = required.width / image.height.toDouble()
        val maxFeasible = image.width.toDouble() / required.height.coerceAtLeast(1.0)
        if (minFeasible > maxFeasible + EPS) return null

        val styleMin: Double
        val styleMax: Double
        when (layout) {
            SubjectLayout.PORTRAIT -> {
                styleMin = 0.60
                styleMax = 0.86
            }
            SubjectLayout.LANDSCAPE -> {
                styleMin = 1.20
                styleMax = 1.85
            }
        }

        val naturalMin = max(minFeasible, styleMin)
        val naturalMax = min(maxFeasible, styleMax)
        return if (naturalMin <= naturalMax + EPS) {
            preferred.coerceIn(naturalMin, naturalMax)
        } else {
            // The subject/source physically cannot fit a normal photo ratio. Relax only as much as needed.
            preferred.coerceIn(minFeasible, maxFeasible)
        }
    }

    private fun fitAspectInside(image: ImageSize, required: RectD, aspect: Double): Pair<Double, Double>? {
        if (aspect <= 0.0) return null
        val cropH = max(required.height, required.width / aspect)
        val cropW = cropH * aspect
        if (cropW > image.width + EPS || cropH > image.height + EPS) return null
        return cropW to cropH
    }

    /** Keep small/distant people in their scene instead of turning a tiny detection into an extreme zoom. */
    private fun expandForSceneContext(
        image: ImageSize,
        subject: RectD,
        cropW: Double,
        cropH: Double,
    ): Pair<Double, Double> {
        val imageArea = image.width.toDouble() * image.height.toDouble()
        val subjectFraction = (subject.area / imageArea).coerceIn(0.0, 1.0)
        val minCropAreaFraction = when {
            subjectFraction < 0.035 -> 0.55
            subjectFraction < 0.08 -> 0.42
            subjectFraction < 0.14 -> 0.30
            else -> 0.0
        }
        if (minCropAreaFraction <= 0.0) return cropW to cropH

        val currentArea = cropW * cropH
        val targetArea = imageArea * minCropAreaFraction
        if (currentArea >= targetArea) return cropW to cropH

        val desiredScale = sqrt(targetArea / currentArea)
        val maxScale = min(image.width / cropW, image.height / cropH)
        val scale = min(desiredScale, maxScale).coerceAtLeast(1.0)
        return cropW * scale to cropH * scale
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

        // Shift minimally again if centring did not fully contain the safe subject bounds.
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
