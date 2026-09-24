package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Crop planner calibrated against the user's manual VideoFrames examples.
 *
 * Rules:
 *  - keep every selected main person inside the crop;
 *  - start from ~5% breathing room on every free side;
 *  - crop both sides whenever source pixels allow it;
 *  - never stretch/resize the image;
 *  - only widen/tall the frame when the raw 5% crop would become unnaturally narrow/wide;
 *  - aspect correction expands in a balanced way, instead of dumping all spare background on one side;
 *  - if a person genuinely reaches the source edge, keep that source edge rather than cutting the person.
 */
object WrestlingCropPlanner {
    // To leave 5% of the FINAL crop on each opposite side:
    // m / (subject + 2m) = 0.05  =>  m ~= subject / 18.
    private const val SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME = 1.0 / 18.0
    private const val MIN_SIDE_IMAGE_MARGIN = 0.004
    private const val MIN_VERTICAL_IMAGE_MARGIN = 0.005

    // Observed useful shape envelope in the user's manual reference set. This is not a forced output
    // ratio; it is only a guard against a very thin/tall or extremely flat accidental crop.
    private const val MIN_REFERENCE_ASPECT = 0.58
    private const val MAX_REFERENCE_ASPECT = 1.78

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (clean.isEmpty()) return full(image)

        val subject = union(clean).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val fivePercent = fivePercentFrame(subject, image)
        val shaped = fitReferenceAspectBalanced(fivePercent, image)
        return shaped.clampTo(image).toPixelRect(image)
    }

    /** Tight crop around the main one/two-person union. No edge snapping and no recentring. */
    private fun fivePercentFrame(subject: RectD, image: ImageSize): RectD {
        val sideMargin = maxOf(
            subject.width * SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME,
            image.width * MIN_SIDE_IMAGE_MARGIN,
        )
        val verticalMargin = maxOf(
            subject.height * SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME,
            image.height * MIN_VERTICAL_IMAGE_MARGIN,
        )

        return RectD(
            left = (subject.left - sideMargin).coerceAtLeast(0.0),
            top = (subject.top - verticalMargin).coerceAtLeast(0.0),
            right = (subject.right + sideMargin).coerceAtMost(image.width.toDouble()),
            bottom = (subject.bottom + verticalMargin).coerceAtMost(image.height.toDouble()),
        )
    }

    /**
     * A standing person can produce a mathematically correct 5% crop that is visually too thin;
     * a fully horizontal action can do the opposite. In those two cases we add context, but we add
     * it equally to both sides first. Only when a source edge blocks one side does the remainder go
     * to the other side. This removes the old one-sided-background effect.
     */
    private fun fitReferenceAspectBalanced(rect: RectD, image: ImageSize): RectD {
        val ratio = rect.width / rect.height.coerceAtLeast(1.0)
        return when {
            ratio < MIN_REFERENCE_ASPECT -> {
                val targetWidth = (rect.height * MIN_REFERENCE_ASPECT)
                    .coerceAtMost(image.width.toDouble())
                expandWidthBalanced(rect, targetWidth, image)
            }
            ratio > MAX_REFERENCE_ASPECT -> {
                val targetHeight = (rect.width / MAX_REFERENCE_ASPECT)
                    .coerceAtMost(image.height.toDouble())
                expandHeightBalanced(rect, targetHeight, image)
            }
            else -> rect
        }
    }

    private fun expandWidthBalanced(rect: RectD, targetWidth: Double, image: ImageSize): RectD {
        val delta = (targetWidth - rect.width).coerceAtLeast(0.0)
        if (delta <= 0.0) return rect

        val availableLeft = rect.left.coerceAtLeast(0.0)
        val availableRight = (image.width.toDouble() - rect.right).coerceAtLeast(0.0)
        val (leftAdd, rightAdd) = distributeBalanced(delta, availableLeft, availableRight)
        return RectD(rect.left - leftAdd, rect.top, rect.right + rightAdd, rect.bottom)
    }

    private fun expandHeightBalanced(rect: RectD, targetHeight: Double, image: ImageSize): RectD {
        val delta = (targetHeight - rect.height).coerceAtLeast(0.0)
        if (delta <= 0.0) return rect

        val availableTop = rect.top.coerceAtLeast(0.0)
        val availableBottom = (image.height.toDouble() - rect.bottom).coerceAtLeast(0.0)
        val (topAdd, bottomAdd) = distributeBalanced(delta, availableTop, availableBottom)
        return RectD(rect.left, rect.top - topAdd, rect.right, rect.bottom + bottomAdd)
    }

    /** Equal first; spill only the blocked remainder to the opposite source side. */
    private fun distributeBalanced(delta: Double, beforeSpace: Double, afterSpace: Double): Pair<Double, Double> {
        if (delta <= 0.0) return 0.0 to 0.0

        val half = delta / 2.0
        var beforeAdd = minOf(half, beforeSpace)
        var afterAdd = minOf(half, afterSpace)
        var remaining = (delta - beforeAdd - afterAdd).coerceAtLeast(0.0)

        if (remaining > 0.0) {
            val beforeRoom = (beforeSpace - beforeAdd).coerceAtLeast(0.0)
            val afterRoom = (afterSpace - afterAdd).coerceAtLeast(0.0)

            if (beforeRoom >= afterRoom) {
                val extraBefore = minOf(remaining, beforeRoom)
                beforeAdd += extraBefore
                remaining -= extraBefore
                if (remaining > 0.0) afterAdd += minOf(remaining, afterRoom)
            } else {
                val extraAfter = minOf(remaining, afterRoom)
                afterAdd += extraAfter
                remaining -= extraAfter
                if (remaining > 0.0) beforeAdd += minOf(remaining, beforeRoom)
            }
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
