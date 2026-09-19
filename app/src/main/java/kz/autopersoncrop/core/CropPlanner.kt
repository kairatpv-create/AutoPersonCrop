package kz.autopersoncrop.core

import kotlin.math.abs
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
 * Fixed photographic crop frames:
 * - portrait 2:3;
 * - landscape 3:2.
 *
 * The planner works on a logical main scene rather than on a single detected person. That main
 * scene may contain one person, several standing people, several lying people, or a mixed pose
 * group such as one person lying while another sits/stands/lies next to or on top of them.
 *
 * Portrait keeps about 5% breathing room above/below the logical scene and derives side crop from
 * 2:3. Landscape keeps about 5% left/right and derives top/bottom crop from 3:2. The planner
 * compares both valid frames and picks the tighter natural composition while preserving all main
 * subjects. No canvas, black bars, stretching or squashing are ever introduced.
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

        val selected = selectPrincipalSubjects(image, people)
        val subject = union(selected).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return fullImage(image, subject)

        val requestedMargin = marginFraction.coerceIn(0.0, 0.15)
        val portrait = buildCandidate(
            image = image,
            selected = selected,
            subject = subject,
            layout = SubjectLayout.PORTRAIT,
            aspect = PORTRAIT_ASPECT,
            requestedMargin = requestedMargin,
        )
        val landscape = buildCandidate(
            image = image,
            selected = selected,
            subject = subject,
            layout = SubjectLayout.LANDSCAPE,
            aspect = LANDSCAPE_ASPECT,
            requestedMargin = requestedMargin,
        )

        val winner = when {
            portrait == null && landscape == null -> null
            portrait == null -> landscape
            landscape == null -> portrait
            else -> if (portrait.score <= landscape.score) portrait else landscape
        }

        if (winner == null) return fullImage(image, subject)

        return CropPlan(
            rect = winner.placed.toPixelRect(image),
            layout = winner.layout,
            targetAspect = winner.aspect,
            status = when {
                winner.margin + EPS < requestedMargin -> CropStatus.ADAPTIVE_MARGIN
                winner.placed.shifted -> CropStatus.SHIFTED_TO_IMAGE_EDGE
                else -> CropStatus.EXACT
            },
            subjectBounds = subject,
            requiredBounds = winner.required,
        )
    }

    private data class Candidate(
        val layout: SubjectLayout,
        val aspect: Double,
        val margin: Double,
        val required: RectD,
        val placed: Placed,
        val score: Double,
    )

    private fun buildCandidate(
        image: ImageSize,
        selected: List<RectD>,
        subject: RectD,
        layout: SubjectLayout,
        aspect: Double,
        requestedMargin: Double,
    ): Candidate? {
        val margins = marginSteps(requestedMargin)
        for (margin in margins) {
            val required = requiredBounds(subject, image, layout, margin)
            val placed = fitAndPlace(image, required, subject, aspect) ?: continue
            val score = candidateScore(
                image = image,
                selected = selected,
                subject = subject,
                layout = layout,
                placed = placed,
                requestedMargin = requestedMargin,
                actualMargin = margin,
            )
            return Candidate(layout, aspect, margin, required, placed, score)
        }
        return null
    }

    private fun marginSteps(requested: Double): List<Double> {
        val result = mutableListOf<Double>()
        fun add(v: Double) {
            val c = v.coerceAtLeast(0.0)
            if (c <= requested + EPS && result.none { abs(it - c) < EPS }) result += c
        }
        add(requested)
        add(0.04)
        add(0.03)
        add(0.02)
        add(0.01)
        add(0.0)
        return result.sortedDescending()
    }

    /**
     * Compare 2:3 and 3:2 by how economically each frame contains the complete logical scene.
     * Geometry is primary; pose/layout hints only break near-ties. This makes mixed scenes stable:
     * lying+standing, lying+sitting, two lying side by side, or overlapping people are all judged
     * as one composition rather than forcing the pose of a single person onto the whole image.
     */
    private fun candidateScore(
        image: ImageSize,
        selected: List<RectD>,
        subject: RectD,
        layout: SubjectLayout,
        placed: Placed,
        requestedMargin: Double,
        actualMargin: Double,
    ): Double {
        val cropArea = (placed.right - placed.left) * (placed.bottom - placed.top)
        val subjectArea = subject.area.coerceAtLeast(1.0)
        val areaCost = cropArea / subjectArea

        val dx = placed.centerX - subject.centerX
        val dy = placed.centerY - subject.centerY
        val diag = sqrt(image.width.toDouble() * image.width + image.height.toDouble() * image.height)
            .coerceAtLeast(1.0)
        val centreCost = sqrt(dx * dx + dy * dy) / diag * 2.0

        val marginCost = (requestedMargin - actualMargin).coerceAtLeast(0.0) * 3.0
        val cueCost = orientationCuePenalty(selected, subject, layout)

        return areaCost + centreCost + marginCost + cueCost
    }

    /** Small, deliberately conservative orientation hints used only after crop geometry. */
    private fun orientationCuePenalty(
        selected: List<RectD>,
        subject: RectD,
        layout: SubjectLayout,
    ): Double {
        var penalty = 0.0
        val groupRatio = subject.width / subject.height.coerceAtLeast(1.0)

        if (groupRatio >= 1.20 && layout == SubjectLayout.PORTRAIT) penalty += 0.18
        if (groupRatio <= 0.83 && layout == SubjectLayout.LANDSCAPE) penalty += 0.18

        val horizontalBodies = selected.count { it.width >= it.height * 1.18 }
        val verticalBodies = selected.count { it.height >= it.width * 1.18 }
        val count = selected.size.coerceAtLeast(1).toDouble()

        if (horizontalBodies > verticalBodies && layout == SubjectLayout.PORTRAIT) {
            penalty += 0.12 * horizontalBodies / count
        }
        if (verticalBodies > horizontalBodies && layout == SubjectLayout.LANDSCAPE) {
            penalty += 0.08 * verticalBodies / count
        }

        if (selected.size > 1) {
            val xSpan = selected.maxOf { it.centerX } - selected.minOf { it.centerX }
            val ySpan = selected.maxOf { it.centerY } - selected.minOf { it.centerY }
            if (xSpan > ySpan * 1.25 && layout == SubjectLayout.PORTRAIT) penalty += 0.10
            if (ySpan > xSpan * 1.25 && layout == SubjectLayout.LANDSCAPE) penalty += 0.08
        }

        return penalty
    }

    /**
     * Portrait = 5% above/below. Landscape = 5% left/right.
     * A very small cross-axis safety protects hands/shoulders from detector-box rounding; the fixed
     * frame itself determines the actual remaining sides.
     */
    private fun requiredBounds(
        subject: RectD,
        image: ImageSize,
        layout: SubjectLayout,
        margin: Double,
    ): RectD {
        val cross = 0.015
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
    ) {
        val centerX: Double get() = (left + right) / 2.0
        val centerY: Double get() = (top + bottom) / 2.0
    }

    /** Smallest rectangle with the exact target aspect that contains required, centred on focus. */
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

        cropW = min(cropW, image.width.toDouble())
        cropH = min(cropH, image.height.toDouble())

        val maxLeft = image.width - cropW
        val maxTop = image.height - cropH

        val leftMin = max(0.0, required.right - cropW)
        val leftMax = min(required.left, maxLeft)
        val topMin = max(0.0, required.bottom - cropH)
        val topMax = min(required.top, maxTop)
        if (leftMin > leftMax + EPS || topMin > topMax + EPS) return null

        val idealLeft = focus.centerX - cropW / 2.0
        val idealTop = focus.centerY - cropH / 2.0
        val left = idealLeft.coerceIn(leftMin, leftMax)
        val top = idealTop.coerceIn(topMin, topMax)
        val shifted = abs(left - idealLeft) > 0.75 || abs(top - idealTop) > 0.75

        return Placed(left, top, left + cropW, top + cropH, shifted)
    }

    private fun Placed.toPixelRect(image: ImageSize): PixelRect {
        val l = floor(left).toInt().coerceIn(0, image.width - 1)
        val t = floor(top).toInt().coerceIn(0, image.height - 1)
        val r = ceil(right).toInt().coerceIn(l + 1, image.width)
        val b = ceil(bottom).toInt().coerceIn(t + 1, image.height)
        return PixelRect(l, t, r, b)
    }

    /**
     * Build the logical main scene as a connected group around the strongest subject.
     *
     * Strong overlap deliberately keeps people who sit/lie on one another even when one detection
     * box is much smaller. Nearby comparable people are also kept. Small edge fragments are ignored
     * unless they strongly overlap the main scene, preventing an accidental half-person at the edge
     * from stretching the crop.
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

        fun anchorScore(r: RectD): Double {
            val edgePenalty = if (isEdgeFragment(r, image)) 0.16 else 0.0
            return (r.area / maxArea).coerceIn(0.0, 1.0) * 0.76 + centrality(r) * 0.24 - edgePenalty
        }

        val anchor = valid.maxByOrNull(::anchorScore) ?: valid.first()
        val chosen = mutableListOf(anchor)
        val remaining = valid.toMutableList().also { it.remove(anchor) }

        var changed: Boolean
        do {
            changed = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val areaRatio = candidate.area / anchor.area.coerceAtLeast(1.0)
                val stronglyOverlaps = chosen.any { overlapFractionOfSmaller(it, candidate) >= 0.10 }
                val connected = chosen.any { areSceneNeighbours(it, candidate, image) }
                val smallEdgeFragment = isEdgeFragment(candidate, image) && areaRatio < 0.22

                val accept = when {
                    stronglyOverlaps -> true
                    smallEdgeFragment -> false
                    connected && areaRatio >= 0.08 -> true
                    isEdgeFragment(candidate, image) && connected && areaRatio >= 0.35 -> true
                    else -> false
                }

                if (accept) {
                    chosen += candidate
                    iterator.remove()
                    changed = true
                }
            }
        } while (changed)

        return chosen
    }

    private fun areSceneNeighbours(a: RectD, b: RectD, image: ImageSize): Boolean {
        if (overlapFractionOfSmaller(a, b) > 0.0) return true

        val gap = normalizedGap(a, b, image)
        if (gap > 0.14) return false

        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 2.1 && dy <= 1.8
    }

    private fun overlapFractionOfSmaller(a: RectD, b: RectD): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        return intersection / min(a.area, b.area).coerceAtLeast(1.0)
    }

    private fun isEdgeFragment(r: RectD, image: ImageSize): Boolean {
        val edgeX = max(3.0, image.width * 0.010)
        val edgeY = max(3.0, image.height * 0.010)
        return r.left <= edgeX || r.right >= image.width - edgeX || r.top <= edgeY
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

    private const val PORTRAIT_ASPECT = 2.0 / 3.0
    private const val LANDSCAPE_ASPECT = 3.0 / 2.0
    private const val EPS = 1e-6
}
