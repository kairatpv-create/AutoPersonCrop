package kz.autopersoncrop.core

import kz.autopersoncrop.ml.DetectedObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A logical scene subject already selected from raw detector output. */
data class SubjectGroup(
    val objects: List<DetectedObject>,
    val bounds: RectD,
)

/**
 * Selects the principal semantic subject before CropPlanner is called.
 *
 * The analyzer is deliberately class-agnostic: people, vehicles, animals and COCO objects compete
 * by confidence, visible area and composition. Nearby/overlapping detections can form a group,
 * while small edge fragments are prevented from stretching the crop.
 */
object SceneAnalyzer {
    fun analyze(image: ImageSize, detections: List<DetectedObject>): SubjectGroup? {
        if (detections.isEmpty()) return null

        val valid = detections.mapNotNull { detected ->
            val box = clamp(detected.boundingBox, image) ?: return@mapNotNull null
            detected.copy(boundingBox = box)
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return SubjectGroup(valid, valid.first().boundingBox)

        val maxArea = valid.maxOf { it.boundingBox.area }.coerceAtLeast(1.0)
        val imageCx = image.width / 2.0
        val imageCy = image.height / 2.0
        val halfDiag = sqrt(imageCx * imageCx + imageCy * imageCy).coerceAtLeast(1.0)

        fun centrality(r: RectD): Double {
            val dx = r.centerX - imageCx
            val dy = r.centerY - imageCy
            return (1.0 - sqrt(dx * dx + dy * dy) / halfDiag).coerceIn(0.0, 1.0)
        }

        fun anchorScore(o: DetectedObject): Double {
            val r = o.boundingBox
            val size = (r.area / maxArea).coerceIn(0.0, 1.0)
            val confidence = o.confidence.toDouble().coerceIn(0.0, 1.0)
            val edgePenalty = if (isEdgeFragment(r, image) && r.area / maxArea < 0.25) 0.18 else 0.0
            return size * 0.58 + centrality(r) * 0.24 + confidence * 0.18 - edgePenalty
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
                val r = candidate.boundingBox
                val anchorArea = anchor.boundingBox.area.coerceAtLeast(1.0)
                val areaRatio = r.area / anchorArea
                val stronglyOverlaps = chosen.any {
                    overlapFractionOfSmaller(it.boundingBox, r) >= 0.10
                }
                val connected = chosen.any { areSceneNeighbours(it.boundingBox, r, image) }
                val smallEdgeFragment = isEdgeFragment(r, image) && areaRatio < 0.22
                val visuallyMeaningful = areaRatio >= 0.08 ||
                    (candidate.confidence >= 0.55f && centrality(r) >= 0.65)

                val accept = when {
                    stronglyOverlaps -> true
                    smallEdgeFragment -> false
                    connected && visuallyMeaningful -> true
                    isEdgeFragment(r, image) && connected && areaRatio >= 0.35 -> true
                    else -> false
                }

                if (accept) {
                    chosen += candidate
                    iterator.remove()
                    changed = true
                }
            }
        } while (changed)

        val boxes = chosen.map { it.boundingBox }
        return SubjectGroup(chosen, union(boxes))
    }

    private fun clamp(r: RectD, image: ImageSize): RectD? {
        val l = r.left.coerceIn(0.0, image.width.toDouble())
        val t = r.top.coerceIn(0.0, image.height.toDouble())
        val rr = r.right.coerceIn(l, image.width.toDouble())
        val b = r.bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, rr, b).takeIf { it.width >= 3.0 && it.height >= 3.0 }
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
        return r.left <= edgeX || r.right >= image.width - edgeX ||
            r.top <= edgeY || r.bottom >= image.height - edgeY
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
}
