package kz.autopersoncrop.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selects the main wrestling action from all detected people.
 *
 * Foreground scale and physical interaction matter much more than image-centre position. A real
 * wrestler at the edge must not lose to a smaller central referee/spectator. Multiple detector
 * passes may contribute boxes, so duplicate rejection is deliberately conservative: two overlapping
 * wrestlers are preserved, while near-identical observations of one person are removed.
 */
object WrestlingSubjectSelector {
    fun select(image: ImageSize, people: List<RectD>): List<RectD> {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val valid = deduplicate(
            people.map { it.clampTo(image) }
                .filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
        )
        if (valid.isEmpty()) return listOf(people.first().clampTo(image))
        if (valid.size == 1) return valid

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

        fun edgePenalty(r: RectD): Double {
            val relative = r.area / maxArea
            if (!isEdgeFragment(r, image)) return 0.0
            return when {
                relative < 0.06 -> 0.50
                relative < 0.15 -> 0.32
                relative < 0.30 -> 0.14
                relative < 0.45 -> 0.03
                else -> 0.0
            }
        }

        fun relation(a: RectD, b: RectD): Double {
            val overlap = overlapFractionOfSmaller(a, b)
            val gap = normalizedGap(a, b, image)
            val proximity = (1.0 - gap / 0.17).coerceIn(0.0, 1.0)
            val larger = max(a.area, b.area).coerceAtLeast(1.0)
            val balance = sqrt((min(a.area, b.area) / larger).coerceIn(0.0, 1.0))
            return (overlap * 0.53 + proximity * 0.34 + balance * 0.13).coerceIn(0.0, 1.0)
        }

        data class PairChoice(
            val a: RectD,
            val b: RectD,
            val score: Double,
            val interaction: Double,
            val dominance: Double,
            val support: Double,
            val unionFraction: Double,
        )

        var bestPair: PairChoice? = null
        for (i in 0 until valid.lastIndex) {
            for (j in i + 1 until valid.size) {
                val a = valid[i]
                val b = valid[j]
                if (isLikelyDuplicate(a, b)) continue

                val overlap = overlapFractionOfSmaller(a, b)
                val gap = normalizedGap(a, b, image)
                val centerDistance = normalizedCenterDistance(a, b)
                val larger = max(a.area, b.area).coerceAtLeast(1.0)
                val sizeBalance = sqrt((min(a.area, b.area) / larger).coerceIn(0.0, 1.0))

                val physicallyLinked = when {
                    overlap >= 0.022 -> true
                    gap <= 0.045 && sizeBalance >= 0.16 -> true
                    gap <= 0.090 && sizeBalance >= 0.24 && centerDistance <= 2.55 -> true
                    gap <= 0.130 && sizeBalance >= 0.40 && centerDistance <= 2.05 -> true
                    else -> false
                }
                if (!physicallyLinked) continue

                val relativeA = (a.area / maxArea).coerceIn(0.0, 1.0)
                val relativeB = (b.area / maxArea).coerceIn(0.0, 1.0)
                val dominance = max(relativeA, relativeB)
                val support = min(relativeA, relativeB)
                val pairUnion = union(a, b)
                val unionAreaFraction = (pairUnion.area / imageArea.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)

                // Reject small background pairs unless overlap is strong enough to look like one
                // wrestling action. A small second box is allowed when it is heavily occluded.
                if (dominance < 0.32 && unionAreaFraction < 0.070) continue
                if (support < 0.085 && overlap < 0.14) continue

                val pairScale = sqrt(((a.area + b.area) / (2.0 * maxArea)).coerceIn(0.0, 1.0))
                val interaction = relation(a, b)
                val pairCentrality = centrality(pairUnion)
                val fullVisibility = (if (isFullyVisible(image, a)) 0.5 else 0.0) +
                    (if (isFullyVisible(image, b)) 0.5 else 0.0)
                val pairEdgePenalty = (edgePenalty(a) + edgePenalty(b)) * 0.20

                val score = interaction * 0.33 +
                    pairScale * 0.24 +
                    dominance * 0.25 +
                    sizeBalance * 0.08 +
                    pairCentrality * 0.03 +
                    fullVisibility * 0.07 -
                    pairEdgePenalty

                val choice = PairChoice(a, b, score, interaction, dominance, support, unionAreaFraction)
                val previousBest = bestPair
                if (previousBest == null || choice.score > previousBest.score) bestPair = choice
            }
        }

        val confidentPair = bestPair?.takeIf {
            it.score >= PAIR_SCORE_THRESHOLD &&
                it.interaction >= PAIR_INTERACTION_THRESHOLD &&
                it.dominance >= 0.32 &&
                (it.support >= 0.085 || overlapFractionOfSmaller(it.a, it.b) >= 0.14)
        }
        if (confidentPair != null) {
            val a = confidentPair.a
            val b = confidentPair.b
            val aFull = isFullyVisible(image, a)
            val bFull = isFullyVisible(image, b)

            // Previous versions discarded any clipped partner too aggressively. Keep a clipped person
            // when it is comparable in size to the complete wrestler: that is usually a real main
            // wrestler at the source edge. Ignore it only when it is clearly a minor edge fragment.
            if (aFull xor bFull) {
                val full = if (aFull) a else b
                val clipped = if (aFull) b else a
                val overlap = overlapFractionOfSmaller(full, clipped)
                val linked = overlap >= 0.02 || normalizedGap(full, clipped, image) <= 0.105
                val clippedMinor = clipped.area <= full.area * 0.62
                val clippedThin = min(
                    clipped.width / full.width.coerceAtLeast(1.0),
                    clipped.height / full.height.coerceAtLeast(1.0),
                ) <= 0.40
                if (linked && clippedMinor && (clippedThin || overlap < 0.16)) return listOf(full)
            }
            return listOf(a, b)
        }

        fun anchorScore(r: RectD): Double {
            val relativeArea = sqrt((r.area / maxArea).coerceIn(0.0, 1.0))
            val bestInteraction = valid.asSequence()
                .filter { it !== r && !isLikelyDuplicate(r, it) }
                .map { relation(r, it) }
                .maxOrNull() ?: 0.0
            val imageFraction = (r.area / imageArea.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            val foregroundBonus = sqrt(imageFraction).coerceIn(0.0, 0.40) * 0.12
            val fullBonus = if (isFullyVisible(image, r)) 0.035 else 0.0

            return relativeArea * 0.79 +
                centrality(r) * 0.04 +
                bestInteraction * 0.10 +
                foregroundBonus +
                fullBonus -
                edgePenalty(r)
        }

        return listOf(valid.maxByOrNull(::anchorScore) ?: valid.first())
    }

    fun isFullyVisible(image: ImageSize, r: RectD): Boolean {
        val edgeX = max(3.0, image.width * FULL_VISIBILITY_EDGE_FRACTION)
        val edgeY = max(3.0, image.height * FULL_VISIBILITY_EDGE_FRACTION)
        return r.left > edgeX &&
            r.right < image.width - edgeX &&
            r.top > edgeY &&
            r.bottom < image.height - edgeY
    }

    private fun deduplicate(input: List<RectD>): List<RectD> {
        if (input.size <= 1) return input
        val keep = ArrayList<RectD>()
        for (candidate in input.sortedByDescending { it.area }) {
            if (keep.none { isLikelyDuplicate(it, candidate) }) keep += candidate
        }
        return keep
    }

    private fun isLikelyDuplicate(a: RectD, b: RectD): Boolean {
        val iou = overlapIoU(a, b)
        if (iou >= 0.92) return true
        if (iou < 0.84) return false
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
        val edgeX = max(3.0, image.width * 0.012)
        val edgeY = max(3.0, image.height * 0.012)
        return r.left <= edgeX ||
            r.right >= image.width - edgeX ||
            r.top <= edgeY ||
            r.bottom >= image.height - edgeY
    }

    private fun union(a: RectD, b: RectD): RectD = RectD(
        min(a.left, b.left),
        min(a.top, b.top),
        max(a.right, b.right),
        max(a.bottom, b.bottom),
    )

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }

    private const val PAIR_SCORE_THRESHOLD = 0.47
    private const val PAIR_INTERACTION_THRESHOLD = 0.275
    private const val FULL_VISIBILITY_EDGE_FRACTION = 0.008
}
