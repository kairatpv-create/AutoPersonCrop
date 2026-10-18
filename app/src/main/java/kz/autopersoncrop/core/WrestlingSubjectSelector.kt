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
    fun select(
        image: ImageSize,
        people: List<RectD>,
        focus: RectD? = null,
        controlledInput: Boolean = false,
    ): List<RectD> {
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
        val sceneCandidates = if (controlledInput) {
            // In this workflow the source contains one or two wanted people.
            // A stationary partner outside the moving silhouette is still a
            // main subject, including a standing person next to a lying one.
            valid
        } else if (focus != null) {
            val near = valid.filter { motionRelevance(it, focus) >= 0.10 }
            // If temporal evidence confidently locates the active action away from ALL YOLO
            // detections, do not silently crop a spectator or an empty wall.
            require(near.isNotEmpty()) { "Нет подтверждённой детекции людей в области движения; файл оставлен без обрезки" }
            near.filterNot { incidentalStandingBystander(it, near, image) }.ifEmpty { near }
        } else {
            valid.filterNot { incidentalStandingBystander(it, valid, image) }.ifEmpty { valid }
        }
        // Multiple detector views can describe a whole overlapping pair as well as
        // fragments of either body. Nested rectangles do not provide independent
        // interaction evidence. Keep their containing envelope, so duplicate votes
        // cannot displace the other main person.
        val actionCandidates = sceneCandidates.filterIndexed { index, box ->
            sceneCandidates.withIndex().none { (otherIndex, other) ->
                otherIndex != index &&
                    coversEnvelope(other, box, image) &&
                    (other.area > box.area * 1.08 ||
                        (sameObservation(other, box) && otherIndex < index))
            }
        }.ifEmpty { sceneCandidates }

        val primary = actionCandidates.withIndex().maxByOrNull { (index, rect) ->
            1.0 - index * 0.08 +
                (focus?.let { motionRelevance(rect, it) * if (controlledInput) 0.4 else 4.0 } ?: 0.0)
        }!!.value
        val second = actionCandidates.asSequence()
            .filter { it !== primary && !sameObservation(primary, it) }
            .filter { addedArea(primary, it) >= it.area * 0.12 }
            .filter { it.area >= primary.area * MIN_SECOND_AREA_RATIO }
            .filter { plausiblePartner(primary, it, image) }
            .maxByOrNull { partnerScore(primary, it, image) +
                (focus?.let { region -> motionRelevance(it, region) * if (controlledInput) 0.2 else 2.0 } ?: 0.0) }

        val chosen = if (second != null) listOf(primary, second) else listOf(primary)
        return chosen.map { main ->
            // Partial hands/feet may appear in only one detector view. Attach
            // those fragments to an already selected person; they do not get a
            // separate vote for which two people are the principal subjects.
            val fragments = sceneCandidates.filter { part ->
                part !== main && part !in chosen &&
                    overlapFractionOfSmaller(main, part) >= 0.55 &&
                    abs(main.centerY - part.centerY) <= max(main.height, part.height) * 0.50 &&
                    part.left >= main.left - image.width * 0.10 &&
                    part.right <= main.right + image.width * 0.10 &&
                    part.top >= main.top - image.height * 0.04 &&
                    part.bottom <= main.bottom + image.height * 0.04
            }
            if (fragments.isEmpty()) main else RectD(
                min(main.left, fragments.minOf { it.left }), min(main.top, fragments.minOf { it.top }),
                max(main.right, fragments.maxOf { it.right }), max(main.bottom, fragments.maxOf { it.bottom }),
            )
        }
    }

    private fun coversEnvelope(outer: RectD, inner: RectD, image: ImageSize): Boolean {
        val dx = image.width * 0.012
        val dy = image.height * 0.012
        return outer.left <= inner.left + dx && outer.top <= inner.top + dy &&
            outer.right >= inner.right - dx && outer.bottom >= inner.bottom - dy
    }

    private fun addedArea(primary: RectD, candidate: RectD): Double {
        val overlapWidth = max(0.0, min(primary.right, candidate.right) - max(primary.left, candidate.left))
        val overlapHeight = max(0.0, min(primary.bottom, candidate.bottom) - max(primary.top, candidate.top))
        return candidate.area - overlapWidth * overlapHeight
    }

    private fun motionRelevance(box: RectD, focus: RectD): Double {
        // Intersection over the smaller region works for overlapping wrestlers: a large
        // reliable person box can cover the moving body without having its center inside it.
        val left = max(box.left, focus.left)
        val top = max(box.top, focus.top)
        val right = min(box.right, focus.right)
        val bottom = min(box.bottom, focus.bottom)
        if (right <= left || bottom <= top) return 0.0
        return ((right - left) * (bottom - top) /
            min(box.area, focus.area).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
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
        return min(overlap, 0.50) * 1.2 +
            addedArea(primary, candidate) / primary.area.coerceAtLeast(1.0) * 0.9 +
            areaBalance * 0.35 - distance * 2.5
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
