package kz.autopersoncrop.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selects one or two people that most likely form the wrestling action.
 *
 * A source-clipped standing person is discarded before ordinary scoring when a physically linked,
 * fully visible lower-positioned partner is available. This implements the project rule that a body
 * part missing from the original photo must never pull the final crop back toward the incomplete
 * person, including through sequence memory.
 */
object WrestlingSubjectSelector {
    fun select(image: ImageSize, people: List<RectD>): List<RectD> {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val valid = people
            .map { it.clampTo(image) }
            .filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
        if (valid.isEmpty()) return listOf(people.first().clampTo(image))
        if (valid.size == 1) return valid

        chooseCompletePartnerForClippedStanding(image, valid)?.let { return listOf(it) }

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
            val proximity = (1.0 - gap / 0.18).coerceIn(0.0, 1.0)
            val larger = max(a.area, b.area).coerceAtLeast(1.0)
            val sizeCompatibility = sqrt((min(a.area, b.area) / larger).coerceIn(0.0, 1.0))
            return overlap * 0.52 + proximity * 0.32 + sizeCompatibility * 0.16
        }

        fun edgePenalty(r: RectD): Double {
            if (!isEdgeFragment(r, image)) return 0.0
            val relative = r.area / maxArea
            return when {
                relative < 0.06 -> 0.36
                relative < 0.15 -> 0.24
                relative < 0.28 -> 0.11
                else -> 0.03
            }
        }

        fun anchorScore(r: RectD): Double {
            val relativeArea = sqrt((r.area / maxArea).coerceIn(0.0, 1.0))
            val bestInteraction = valid.asSequence()
                .filter { it !== r }
                .map { relation(r, it) }
                .maxOrNull() ?: 0.0
            return relativeArea * 0.46 + centrality(r) * 0.27 + bestInteraction * 0.27 - edgePenalty(r)
        }

        val anchor = valid.maxByOrNull(::anchorScore) ?: valid.first()
        data class PartnerCandidate(val box: RectD, val score: Double)

        val partner = valid.asSequence()
            .filter { it !== anchor }
            .mapNotNull { candidate ->
                if (isLikelyDuplicate(anchor, candidate)) return@mapNotNull null

                val overlap = overlapFractionOfSmaller(anchor, candidate)
                val gap = normalizedGap(anchor, candidate, image)
                val smallerToAnchor = candidate.area / anchor.area.coerceAtLeast(1.0)
                val areaRatio = min(smallerToAnchor, 1.0 / smallerToAnchor.coerceAtLeast(1e-6))
                val centerDistance = normalizedCenterDistance(anchor, candidate)
                val smallEdgeFragment = isEdgeFragment(candidate, image) && areaRatio < 0.18 && overlap < 0.10

                val physicallyLinked = when {
                    overlap >= 0.04 -> true
                    gap <= 0.040 && areaRatio >= 0.06 -> true
                    gap <= 0.090 && areaRatio >= 0.15 && centerDistance <= 2.60 -> true
                    gap <= 0.140 && areaRatio >= 0.28 && centerDistance <= 2.10 -> true
                    else -> false
                }
                if (!physicallyLinked || smallEdgeFragment) return@mapNotNull null

                val score = relation(anchor, candidate) + centrality(candidate) * 0.09 - edgePenalty(candidate)
                PartnerCandidate(candidate, score)
            }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= 0.20 }
            ?.box

        if (partner == null) return listOf(anchor)

        val anchorFull = isFullyVisible(image, anchor)
        val partnerFull = isFullyVisible(image, partner)
        if (anchorFull xor partnerFull) {
            return listOf(if (anchorFull) anchor else partner)
        }

        return listOf(anchor, partner)
    }

    fun isFullyVisible(image: ImageSize, r: RectD): Boolean {
        val edgeX = max(3.0, image.width * FULL_VISIBILITY_EDGE_FRACTION)
        val edgeY = max(3.0, image.height * FULL_VISIBILITY_EDGE_FRACTION)
        return r.left > edgeX &&
            r.right < image.width - edgeX &&
            r.top > edgeY &&
            r.bottom < image.height - edgeY
    }

    private fun chooseCompletePartnerForClippedStanding(image: ImageSize, valid: List<RectD>): RectD? {
        val clippedStanding = valid.filter {
            !isFullyVisible(image, it) && it.height >= it.width * 1.05
        }
        if (clippedStanding.isEmpty()) return null

        val complete = valid.filter { isFullyVisible(image, it) }
        if (complete.isEmpty()) return null

        data class Choice(val box: RectD, val score: Double)
        var best: Choice? = null
        for (clipped in clippedStanding) {
            for (candidate in complete) {
                val overlap = overlapFractionOfSmaller(clipped, candidate)
                val gap = normalizedGap(clipped, candidate, image)
                val linked = overlap >= 0.015 || gap <= 0.105
                if (!linked) continue

                val lowerOrCompact =
                    candidate.height <= clipped.height * 0.88 ||
                    candidate.centerY >= clipped.centerY + image.height * 0.025
                if (!lowerOrCompact) continue

                val relativeArea = candidate.area / clipped.area.coerceAtLeast(1.0)
                if (relativeArea < 0.10) continue

                val cx = image.width / 2.0
                val cy = image.height / 2.0
                val dx = (candidate.centerX - cx) / image.width.coerceAtLeast(1).toDouble()
                val dy = (candidate.centerY - cy) / image.height.coerceAtLeast(1).toDouble()
                val central = (1.0 - sqrt(dx * dx + dy * dy) * 1.7).coerceIn(0.0, 1.0)
                val score = overlap * 0.45 + (1.0 - gap / 0.105).coerceIn(0.0, 1.0) * 0.30 +
                    central * 0.15 + min(1.0, relativeArea) * 0.10
                if (best == null || score > best!!.score) best = Choice(candidate, score)
            }
        }
        return best?.box
    }

    private fun isLikelyDuplicate(a: RectD, b: RectD): Boolean {
        val iou = overlapIoU(a, b)
        if (iou < 0.82) return false
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.72) return false
        return normalizedCenterDistance(a, b) <= 0.18
    }

    private fun normalizedCenterDistance(a: RectD, b: RectD): Double {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        val scaleW = max(a.width, b.width).coerceAtLeast(1.0)
        val scaleH = max(a.height, b.height).coerceAtLeast(1.0)
        val scale = sqrt(scaleW * scaleW + scaleH * scaleH).coerceAtLeast(1.0)
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

    private const val FULL_VISIBILITY_EDGE_FRACTION = 0.008
}
