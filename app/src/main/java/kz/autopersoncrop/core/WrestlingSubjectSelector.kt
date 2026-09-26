package kz.autopersoncrop.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selector for controlled input: only one person or two wrestlers can be present.
 * Detector order is treated as reliability order; we no longer sort by box area, because a giant
 * weak box was the main reason previous versions returned almost the full source image.
 */
object WrestlingSubjectSelector {
    fun select(image: ImageSize, people: List<RectD>): List<RectD> {
        require(people.isNotEmpty()) { "At least one person box is required" }

        val nonNegative = people.filterNot {
            it.left < 0.0 || it.top < 0.0 || it.right < 0.0 || it.bottom < 0.0
        }
        require(nonNegative.isNotEmpty()) { "Не удалось надёжно распознать человека в кадре" }

        val valid = nonNegative
            .map { it.clampTo(image) }
            .filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
            .filterNot { frameLike(it, image) }

        require(valid.isNotEmpty()) { "Не удалось получить надёжную рамку человека" }

        val primary = valid.first()
        val second = valid.asSequence()
            .drop(1)
            .filter { !sameObservation(primary, it) }
            .filter { it.area >= primary.area * MIN_SECOND_AREA_RATIO }
            .filter { plausiblePartner(primary, it, image) }
            .firstOrNull()

        return if (second != null) listOf(primary, second) else listOf(primary, primary)
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
        return wf > 0.92 && hf > 0.92
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
