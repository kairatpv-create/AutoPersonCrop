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
 * Screen-filling crop planner.
 *
 * Portrait subject/group:
 * - keep roughly 10% breathing room above and below the visible main subject;
 * - expand/crop the left and right source edges to the phone portrait aspect ratio.
 *
 * Landscape subject/group:
 * - keep roughly 10% breathing room left and right;
 * - expand/crop the top and bottom source edges to the phone landscape aspect ratio.
 *
 * The result is crop-only: no canvas, black bars, stretching or squashing.
 * The selected person/group is kept as close to the exact centre as source boundaries allow.
 */
object CropPlanner {
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

        val shortSide = min(screenWidth.coerceAtLeast(1), screenHeight.coerceAtLeast(1)).toDouble()
        val longSide = max(screenWidth.coerceAtLeast(1), screenHeight.coerceAtLeast(1)).toDouble()
        val portraitAspect = shortSide / longSide
        val landscapeAspect = longSide / shortSide

        var layout = chooseLayout(subject, selected.size)
        var targetAspect = if (layout == SubjectLayout.PORTRAIT) portraitAspect else landscapeAspect

        // If the intended orientation physically cannot contain the detected subject inside the
        // source image at the phone aspect ratio, use the other orientation rather than cut a body.
        if (!canContainSubject(image, subject, targetAspect)) {
            val alternate = if (layout == SubjectLayout.PORTRAIT) SubjectLayout.LANDSCAPE else SubjectLayout.PORTRAIT
            val alternateAspect = if (alternate == SubjectLayout.PORTRAIT) portraitAspect else landscapeAspect
            if (canContainSubject(image, subject, alternateAspect)) {
                layout = alternate
                targetAspect = alternateAspect
            }
        }

        val requestedMargin = marginFraction.coerceIn(0.0, 0.20)
        val margins = buildList {
            add(requestedMargin)
            for (m in listOf(0.08, 0.06, 0.04, 0.02, 0.0)) if (m < requestedMargin) add(m)
        }

        for ((index, margin) in margins.withIndex()) {
            val required = requiredBounds(subject, image, layout, margin)
            val placed = fitAndPlace(image, required, subject, targetAspect) ?: continue
            return CropPlan(
                rect = placed.toPixelRect(image),
                layout = layout,
                targetAspect = targetAspect,
                status = when {
                    index > 0 -> CropStatus.ADAPTIVE_MARGIN
                    placed.shifted -> CropStatus.SHIFTED_TO_IMAGE_EDGE
                    else -> CropStatus.EXACT
                },
                subjectBounds = subject,
                requiredBounds = required,
            )
        }

        // Last safe attempt: exact phone aspect containing the visible subject with no requested
        // breathing room. If even this is impossible, keep the whole source rather than cut a body.
        fitAndPlace(image, subject, subject, targetAspect)?.let { placed ->
            return CropPlan(
                rect = placed.toPixelRect(image),
                layout = layout,
                targetAspect = targetAspect,
                status = CropStatus.ADAPTIVE_MARGIN,
                subjectBounds = subject,
                requiredBounds = subject,
            )
        }

        return fullImage(image, subject)
    }

    private fun chooseLayout(subject: RectD, subjectCount: Int): SubjectLayout {
        val ratio = subject.width / subject.height.coerceAtLeast(1.0)
        return if (subjectCount <= 1) {
            if (ratio > 1.05) SubjectLayout.LANDSCAPE else SubjectLayout.PORTRAIT
        } else {
            // Two people / a group side-by-side should fill a landscape screen; a narrow/tall group
            // remains portrait.
            if (ratio >= 0.92) SubjectLayout.LANDSCAPE else SubjectLayout.PORTRAIT
        }
    }

    private fun canContainSubject(image: ImageSize, subject: RectD, aspect: Double): Boolean {
        val maxWidthAtAspect = min(image.width.toDouble(), image.height * aspect)
        val maxHeightAtAspect = min(image.height.toDouble(), image.width / aspect)
        return subject.width <= maxWidthAtAspect + 1.0 && subject.height <= maxHeightAtAspect + 1.0
    }

    /**
     * Portrait = 10% above/below. Landscape = 10% left/right.
     * A tiny 2% safety on the cross axis prevents hands/shoulders from sitting exactly on an edge.
     */
    private fun requiredBounds(
        subject: RectD,
        image: ImageSize,
        layout: SubjectLayout,
        margin: Double,
    ): RectD {
        val cross = 0.02
        return when (layout) {
            SubjectLayout.PORTRAIT -> RectD(
                left = subject.left - subject.width * cross,
                top = subject.top - subject.height * margin,
                right = subject.right + subject.width * cross,
                bottom = subject.bottom + subject.height * margin,
            ).clampTo(image)
            SubjectLayout.LANDSCAPE -> RectD(
                left = subject.left - subject.width * margin,
                top = subject.top - subject.height * cross,
                right = subject.right + subject.width * margin,
                bottom = subject.bottom + subject.height * cross,
            ).clampTo(image)
        }
    }

    private data class Placed(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val shifted: Boolean,
    )

    /** Smallest rectangle with the exact target aspect that contains required, then centred on focus. */
    private fun fitAndPlace(
        image: ImageSize,
        required: RectD,
        focus: RectD,
        aspect: Double,
    ): Placed? {
        if (aspect <= 0.0) return null

        var cropH = max(required.height, required.width / aspect)
        var cropW = cropH * aspect
        if (cropW > image.width + EPS || cropH > image.height + EPS) return null

        // Numerical cleanup close to source boundaries.
        cropW = min(cropW, image.width.toDouble())
        cropH = min(cropH, image.height.toDouble())

        val maxLeft = image.width - cropW
        val maxTop = image.height - cropH

        // A valid crop containing required has its left/top inside these intervals.
        val leftMin = max(0.0, required.right - cropW)
        val leftMax = min(required.left, maxLeft)
        val topMin = max(0.0, required.bottom - cropH)
        val topMax = min(required.top, maxTop)
        if (leftMin > leftMax + EPS || topMin > topMax + EPS) return null

        val idealLeft = focus.centerX - cropW / 2.0
        val idealTop = focus.centerY - cropH / 2.0
        val left = idealLeft.coerceIn(leftMin, leftMax)
        val top = idealTop.coerceIn(topMin, topMax)
        val shifted = kotlin.math.abs(left - idealLeft) > 0.75 || kotlin.math.abs(top - idealTop) > 0.75

        return Placed(left, top, left + cropW, top + cropH, shifted)
    }

    private fun Placed.toPixelRect(image: ImageSize): PixelRect {
        var l = floor(left).toInt().coerceIn(0, image.width - 1)
        var t = floor(top).toInt().coerceIn(0, image.height - 1)
        var r = ceil(right).toInt().coerceIn(l + 1, image.width)
        var b = ceil(bottom).toInt().coerceIn(t + 1, image.height)
        return PixelRect(l, t, r, b)
    }

    /**
     * 1) Prefer complete main people over body fragments touching a source edge.
     * 2) Keep similarly scaled complete people when they form one group.
     * 3) If everybody is partial, centre by the largest/most central visible body area.
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

    private const val EPS = 1e-6
}
