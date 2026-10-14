package kz.autopersoncrop.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selector for controlled input: only one person or two wrestlers can be present.
 * Detector order is only a weak reliability hint. Referee-like tall boxes must not override
 * a compact wrestling pair. Frame-like boxes are rejected and one real person remains one
 * subject; never duplicate a box to satisfy legacy sequence logic.
 */
object WrestlingSubjectSelector {
    fun select(image: ImageSize, people: List<RectD>): List<RectD> {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val valid = people.asSequence()
            .filterNot { it.left < 0.0 || it.top < 0.0 || it.right < 0.0 || it.bottom < 0.0 }
            .map { it.clampTo(image) }
            .filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
            .filterNot { frameLike(it, image) }
            .toList()

        require(valid.isNotEmpty()) { "Не удалось получить надёжную рамку человека" }

        // In grappling photos a referee can be a much easier YOLO detection than wrestlers:
        // standing upright, extending from the top of the frame to the mat. If independent
        // lower horizontal athlete evidence exists, do not let that incidental standing
        // person determine the crop height. This is a scene-geometry rule, never a
        // filename- or pixel-coordinate-specific exception.
        val actionCandidates = valid.filterNot { incidentalStandingBystander(it, valid, image) }
            .ifEmpty { valid }

        val primary = actionCandidates.withIndex().maxByOrNull { (index, rect) ->
            anchorScore(rect, index, actionCandidates)
        }!!.value
        val second = actionCandidates.asSequence()
            .filter { it !== primary && !sameObservation(primary, it) }
            .filter { it.area >= primary.area * MIN_SECOND_AREA_RATIO }
            .filter { plausiblePartner(primary, it, image) }
            .maxByOrNull { partnerScore(primary, it, image) }

        return if (second != null) listOf(primary, second) else listOf(primary)
    }

    private fun anchorScore(subject: RectD, index: Int, all: List<RectD>): Double {
        // Cross-view detector order is useful, but the overlapping fighting group is a
        // stronger cue than the first tall bystander that a generic detector recognizes.
        val bestInteraction = all.asSequence()
            .filter { it !== subject && !sameObservation(subject, it) }
            .map { overlapFractionOfSmaller(subject, it) }
            .maxOrNull() ?: 0.0
        return 1.0 - index * 0.08 + bestInteraction * 1.8
    }

    private fun incidentalStandingBystander(
        candidate: RectD,
        all: List<RectD>,
        image: ImageSize,
    ): Boolean {
        if (all.size < 2) return false
        val h = image.height.toDouble()
        val w = image.width.toDouble()
        // Never reject a sole standing person. The tall shape must start high in the
        // source and be relatively narrow, unlike the horizontal ground-level action.
        if (candidate.top > h * 0.13 ||
            candidate.height < h * 0.62 ||
            candidate.width > candidate.height * 0.72 ||
            candidate.width > w * 0.42
        ) return false
        return all.any { other ->
            other !== candidate &&
                !sameObservation(candidate, other) &&
                other.area >= candidate.area * 0.12 &&
                other.width >= other.height * 1.08 &&
                other.centerY >= h * 0.52 &&
                other.height < candidate.height * 0.80 &&
                overlapFractionOfSmaller(candidate, other) < 0.15 &&
                abs(candidate.centerX - other.centerX) > max(candidate.width, other.width) * 0.40
        }
    }

    fun isFullyVisible(image: ImageSize, r: RectD): Boolean {
        val edgeX = max(2.0, image.width * 0.006)
        val edgeY = max(2.0, image.height * 0.006)
        return r.left > edgeX &&
            r.right < image.width - edgeX &&
            r.top > edgeY &&
            r.bottom < image.height - edgeY
    }

    private fun frameLike(r: RectD, image: ImageSize): Boolean {
        val wf = r.width / image.width.toDouble()
        val hf = r.height / image.height.toDouble()
        val af = r.area / (image.width.toDouble() * image.height.toDouble())
        return (wf > 0.92 && hf > 0.92) || af > 0.86
    }

    private fun plausiblePartner(a: RectD, b: RectD, image: ImageSize): Boolean {
        if (overlapFractionOfSmaller(a, b) > 0.02) return true
        val horizontalGap = when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0.0
        }
        val verticalGap = when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0.0
        }
        val normalizedGap = sqrt(
            (horizontalGap / image.width.toDouble()).let { it * it } +
                (verticalGap / image.height.toDouble()).let { it * it }
        )
        return normalizedGap <= MAX_PARTNER_GAP
    }

    private fun partnerScore(primary: RectD, candidate: RectD, image: ImageSize): Double {
        // Rank by anatomical proximity and overlap instead of taking the first later
        // detector observation, which might be a referee or an unrelated spectator.
        val overlap = overlapFractionOfSmaller(primary, candidate)
        val areaBalance = min(primary.area, candidate.area) / max(primary.area, candidate.area).coerceAtLeast(1.0)
        val gapX = when {
            primary.right < candidate.left -> candidate.left - primary.right
            candidate.right < primary.left -> primary.left - candidate.right
            else -> 0.0
        }
        val gapY = when {
            primary.bottom < candidate.top -> candidate.top - primary.bottom
            candidate.bottom < primary.top -> primary.top - candidate.bottom
            else -> 0.0
        }
        val distance = sqrt((gapX / image.width).let { it * it } + (gapY / image.height).let { it * it })
        return overlap * 2.0 + areaBalance * 0.35 - distance * 2.5
    }

    private fun sameObservation(a: RectD, b: RectD): Boolean {
        val overlap = overlapIoU(a, b)
        if (overlap >= 0.94) return true
        if (overlap < 0.84) return false

        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.76) return false

        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.10 && dy <= 0.10
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

    private fun overlapIoU(a: RectD, b: RectD): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }

    private const val MIN_SECOND_AREA_RATIO = 0.055
    private const val MAX_PARTNER_GAP = 0.28
}
