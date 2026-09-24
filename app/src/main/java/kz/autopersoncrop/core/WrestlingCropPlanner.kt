package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Edge-aware crop planner tuned to the user's manual VideoFrames reference set.
 *
 * The detector is intentionally allowed a small amount of uncertainty around a person's extremities.
 * We therefore protect head/feet slightly more than the sides, while still keeping empty background
 * close to the user's requested ~5% and never re-centering edge subjects.
 */
object WrestlingCropPlanner {
    private const val SIDE_SUBJECT_MARGIN = 0.0556
    private const val VERTICAL_SUBJECT_MARGIN = 0.072
    private const val MIN_SIDE_IMAGE_MARGIN = 0.006
    private const val MIN_VERTICAL_IMAGE_MARGIN = 0.008

    private const val MIN_REFERENCE_ASPECT = 0.58
    private const val MAX_REFERENCE_ASPECT = 1.78
    private const val SOURCE_EDGE_FRACTION = 0.012
    private const val EDGE_SNAP_MARGIN_MULTIPLIER = 1.45

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (clean.isEmpty()) return full(image)

        val subject = union(clean).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val base = expandSafely(subject, image)
        return fitReferenceAspectWithoutRecentering(base, image).toPixelRect(image)
    }

    private fun expandSafely(subject: RectD, image: ImageSize): RectD {
        val sideMargin = maxOf(
            subject.width * SIDE_SUBJECT_MARGIN,
            image.width * MIN_SIDE_IMAGE_MARGIN,
        )
        val verticalMargin = maxOf(
            subject.height * VERTICAL_SUBJECT_MARGIN,
            image.height * MIN_VERTICAL_IMAGE_MARGIN,
        )

        val edgeX = image.width * SOURCE_EDGE_FRACTION
        val edgeY = image.height * SOURCE_EDGE_FRACTION

        val left = if (subject.left <= maxOf(edgeX, sideMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            0.0
        } else {
            subject.left - sideMargin
        }

        val rightGap = image.width.toDouble() - subject.right
        val right = if (rightGap <= maxOf(edgeX, sideMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            image.width.toDouble()
        } else {
            subject.right + sideMargin
        }

        val top = if (subject.top <= maxOf(edgeY, verticalMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            0.0
        } else {
            subject.top - verticalMargin
        }

        val bottomGap = image.height.toDouble() - subject.bottom
        val bottom = if (bottomGap <= maxOf(edgeY, verticalMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            image.height.toDouble()
        } else {
            subject.bottom + verticalMargin
        }

        return RectD(left, top, right, bottom).clampTo(image)
    }

    /**
     * Keep the crop inside the aspect range observed in the user's manual examples. Correction only
     * EXPANDS a free dimension; it never trims through already selected people. Extra room is biased
     * toward whichever source side actually has room, preserving natural edge composition.
     */
    private fun fitReferenceAspectWithoutRecentering(rect: RectD, image: ImageSize): RectD {
        val ratio = rect.width / rect.height.coerceAtLeast(1.0)
        return when {
            ratio < MIN_REFERENCE_ASPECT -> {
                val targetWidth = (rect.height * MIN_REFERENCE_ASPECT)
                    .coerceAtMost(image.width.toDouble())
                expandWidthWithoutRecentering(rect, targetWidth, image)
            }
            ratio > MAX_REFERENCE_ASPECT -> {
                val targetHeight = (rect.width / MAX_REFERENCE_ASPECT)
                    .coerceAtMost(image.height.toDouble())
                expandHeightWithoutRecentering(rect, targetHeight, image)
            }
            else -> rect
        }.clampTo(image)
    }

    private fun expandWidthWithoutRecentering(rect: RectD, targetWidth: Double, image: ImageSize): RectD {
        val delta = (targetWidth - rect.width).coerceAtLeast(0.0)
        if (delta <= 0.0) return rect

        val availableLeft = rect.left.coerceAtLeast(0.0)
        val availableRight = (image.width.toDouble() - rect.right).coerceAtLeast(0.0)
        val (leftAdd, rightAdd) = distributeExpansion(delta, availableLeft, availableRight)
        return RectD(rect.left - leftAdd, rect.top, rect.right + rightAdd, rect.bottom)
    }

    private fun expandHeightWithoutRecentering(rect: RectD, targetHeight: Double, image: ImageSize): RectD {
        val delta = (targetHeight - rect.height).coerceAtLeast(0.0)
        if (delta <= 0.0) return rect

        val availableTop = rect.top.coerceAtLeast(0.0)
        val availableBottom = (image.height.toDouble() - rect.bottom).coerceAtLeast(0.0)
        val (topAdd, bottomAdd) = distributeExpansion(delta, availableTop, availableBottom)
        return RectD(rect.left, rect.top - topAdd, rect.right, rect.bottom + bottomAdd)
    }

    private fun distributeExpansion(delta: Double, beforeSpace: Double, afterSpace: Double): Pair<Double, Double> {
        val totalSpace = beforeSpace + afterSpace
        if (delta <= 0.0 || totalSpace <= 0.0) return 0.0 to 0.0

        var beforeAdd = (delta * beforeSpace / totalSpace).coerceAtMost(beforeSpace)
        var afterAdd = (delta - beforeAdd).coerceAtMost(afterSpace)
        var remaining = (delta - beforeAdd - afterAdd).coerceAtLeast(0.0)

        if (remaining > 0.0) {
            val extraBefore = remaining.coerceAtMost((beforeSpace - beforeAdd).coerceAtLeast(0.0))
            beforeAdd += extraBefore
            remaining -= extraBefore
        }
        if (remaining > 0.0) {
            val extraAfter = remaining.coerceAtMost((afterSpace - afterAdd).coerceAtLeast(0.0))
            afterAdd += extraAfter
        }
        return beforeAdd to afterAdd
    }

    private fun RectD.toPixelRect(image: ImageSize): PixelRect {
        val l = floor(left).toInt().coerceIn(0, image.width - 1)
        val t = floor(top).toInt().coerceIn(0, image.height - 1)
        val r = ceil(right).toInt().coerceIn(l + 1, image.width)
        val b = ceil(bottom).toInt().coerceIn(t + 1, image.height)
        return PixelRect(l, t, r, b)
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom },
    )

    private fun RectD.clampTo(image: ImageSize): RectD {
        val l = left.coerceIn(0.0, image.width.toDouble())
        val t = top.coerceIn(0.0, image.height.toDouble())
        val r = right.coerceIn(l, image.width.toDouble())
        val b = bottom.coerceIn(t, image.height.toDouble())
        return RectD(l, t, r, b)
    }

    private fun full(image: ImageSize) = PixelRect(0, 0, image.width, image.height)
}
