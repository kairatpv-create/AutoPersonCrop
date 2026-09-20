package kz.autopersoncrop.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selects the one or two people that most likely form the wrestling action.
 *
 * The selector is intentionally specialised for combat-sport photography:
 * - only person detections are considered;
 * - at most two principal subjects are returned;
 * - pose/orientation is irrelevant (standing, sitting, lying, stacked or entangled);
 * - overlap and physical proximity strongly favour a second wrestler;
 * - small edge/background people are discouraged so judges and spectators do not stretch the crop.
 *
 * CropPlanner remains responsible only for photographic framing after this selection.
 */
object WrestlingSubjectSelector {
    fun select(image: ImageSize, people: List<RectD>): List<RectD> {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val valid = people
            .map { it.clampTo(image) }
            .filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
        if (valid.isEmpty()) return listOf(people.first().clampTo(image))
        if (valid.size == 1) return valid

        val maxArea = valid.maxOf { it.area }.coerceAtLeast(1.0)
        val imageCx = image.width / 2.0
        val imageCy = image.height / 2.0
        val halfDiag = sqrt(imageCx * imageCx + imageCy * imageCy).coerceAtLeast(1.0)

        fun centrality(r: RectD): Double {
            val dx = r.centerX - imageCx
            val dy = r.centerY - imageCy
            return (1.0 - sqrt(dx * dx + dy * dy) / halfDiag).coerceIn(0.0, 1.0)
        }

        fun relation(a: RectD, b: RectD): Double {
            val overlap = overlapFractionOfSmaller(a, b)
            val gap = normalizedGap(a, b, image)
            val proximity = (1.0 - gap / 0.14).coerceIn(0.0, 1.0)
            val larger = max(a.area, b.area).coerceAtLeast(1.0)
            val sizeCompatibility = sqrt((min(a.area, b.area) / larger).coerceIn(0.0, 1.0))
            return overlap * 0.55 + proximity * 0.30 + sizeCompatibility * 0.15
        }

        fun edgePenalty(r: RectD): Double {
            if (!isEdgeFragment(r, image)) return 0.0
            val relative = r.area / maxArea
            return when {
                relative < 0.08 -> 0.32
                relative < 0.18 -> 0.22
                relative < 0.30 -> 0.10
                else -> 0.03
            }
        }

        fun anchorScore(r: RectD): Double {
            val relativeArea = sqrt((r.area / maxArea).coerceIn(0.0, 1.0))
            val bestInteraction = valid.asSequence()
                .filter { it !== r }
                .map { relation(r, it) }
                .maxOrNull() ?: 0.0
            return relativeArea * 0.52 + centrality(r) * 0.20 + bestInteraction * 0.28 - edgePenalty(r)
        }

        val anchor = valid.maxByOrNull(::anchorScore) ?: valid.first()

        data class PartnerCandidate(val box: RectD, val score: Double)

        val partner = valid.asSequence()
            .filter { it !== anchor }
            .mapNotNull { candidate ->
                val overlap = overlapFractionOfSmaller(anchor, candidate)
                val gap = normalizedGap(anchor, candidate, image)
                val smallerToAnchor = candidate.area / anchor.area.coerceAtLeast(1.0)
                val areaRatio = min(smallerToAnchor, 1.0 / smallerToAnchor.coerceAtLeast(1e-6))
                val centerDistance = normalizedCenterDistance(anchor, candidate)
                val smallEdgeFragment = isEdgeFragment(candidate, image) && areaRatio < 0.25 && overlap < 0.12

                val physicallyLinked = when {
                    overlap >= 0.06 -> true
                    gap <= 0.035 && areaRatio >= 0.08 -> true
                    gap <= 0.070 && areaRatio >= 0.18 && centerDistance <= 2.40 -> true
                    gap <= 0.110 && areaRatio >= 0.35 && centerDistance <= 1.90 -> true
                    else -> false
                }
                if (!physicallyLinked || smallEdgeFragment) return@mapNotNull null

                val score = relation(anchor, candidate) + centrality(candidate) * 0.08 - edgePenalty(candidate)
                PartnerCandidate(candidate, score)
            }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= 0.23 }
            ?.box

        return if (partner != null) listOf(anchor, partner) else listOf(anchor)
    }

    private fun normalizedCenterDistance(a: RectD, b: RectD): Double {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        val scale = sqrt(
            max(a.width, b.width).coerceAtLeast(1.0) * max(a.width, b.width).coerceAtLeast(1.0) +
                max(a.height, b.height).coerceAtLeast(1.0) * max(a.height, b.height).coerceAtLeast(1.0)
        ).coerceAtLeast(1.0)
        return sqrt(dx * dx + dy * dy) / scale
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

    private fun isEdgeFragment(r: RectD, image: ImageSize): Boolean {
        val edgeX = max(3.0, image.width * 0.010)
        val edgeY = max(3.0, image.height * 0.010)
        return r.left <= edgeX || r.right >= image.width - edgeX || r.top <= edgeY || r.bottom >= image.height - edgeY
    }

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }
}
