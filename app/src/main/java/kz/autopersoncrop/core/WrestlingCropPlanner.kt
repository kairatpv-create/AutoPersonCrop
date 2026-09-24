package kz.autopersoncrop.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Crop planner tuned from the user's manually cropped VideoFrames reference set.
 *
 * Reference behaviour:
 * - keep about 5% breathing room around the selected main person / wrestling pair;
 * - never crop inside selected subject geometry, so detected head/feet stay protected;
 * - standing, kneeling and ground action use the same edge-aware base rule;
 * - a person/action near a source edge stays near that edge: never re-centre just for symmetry;
 * - avoid unnaturally narrow or ultra-wide crops. The reference set stays roughly within
 *   width/height 0.58..1.78, so aspect correction EXPANDS the free dimension only;
 * - aspect expansion is distributed toward available source space, preserving an off-centre subject.
 *
 * The planner never rotates, stretches, centres or pads the image.
 */
object WrestlingCropPlanner {
    // 5.56% of subject size gives about a 5% border in the resulting frame when both sides are free.
    private const val SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME = 0.0556

    // Bounds measured from the confidently matched manual crops in VideoFrames.rar.
    private const val MIN_REFERENCE_ASPECT = 0.58
    private const val MAX_REFERENCE_ASPECT = 1.78

    private const val SOURCE_EDGE_FRACTION = 0.012
    private const val EDGE_SNAP_MARGIN_MULTIPLIER = 1.50

    fun plan(image: ImageSize, subjects: List<RectD>): PixelRect {
        require(subjects.isNotEmpty()) { "At least one wrestling subject is required" }

        val clean = subjects
            .map { it.clampTo(image) }
            .filter { it.width >= 2.0 && it.height >= 2.0 }
        if (clean.isEmpty()) return full(image)

        val subject = union(clean).clampTo(image)
        if (subject.width < 2.0 || subject.height < 2.0) return full(image)

        val base = expandWithReferenceMargin(subject, image)
        val fitted = fitReferenceAspectWithoutRecentering(base, image)
        return fitted.toPixelRect(image)
    }

    private fun expandWithReferenceMargin(subject: RectD, image: ImageSize): RectD {
        val leftMargin = subject.width * SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME
        val rightMargin = subject.width * SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME
        val topMargin = subject.height * SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME
        val bottomMargin = subject.height * SUBJECT_MARGIN_FOR_FIVE_PERCENT_FRAME

        val edgeX = image.width * SOURCE_EDGE_FRACTION
        val edgeY = image.height * SOURCE_EDGE_FRACTION

        // The manual crops often keep the natural image/floor edge when the action is already close
        // to it. Snap only when the remaining gap is comparable to the wanted breathing room.
        val left = if (subject.left <= maxOf(edgeX, leftMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            0.0
        } else {
            subject.left - leftMargin
        }
        val rightGap = image.width.toDouble() - subject.right
        val right = if (rightGap <= maxOf(edgeX, rightMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            image.width.toDouble()
        } else {
            subject.right + rightMargin
        }
        val top = if (subject.top <= maxOf(edgeY, topMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            0.0
        } else {
            subject.top - topMargin
        }
        val bottomGap = image.height.toDouble() - subject.bottom
        val bottom = if (bottomGap <= maxOf(edgeY, bottomMargin * EDGE_SNAP_MARGIN_MULTIPLIER)) {
            image.height.toDouble()
        } else {
            subject.bottom + bottomMargin
        }

        return RectD(left, top, right, bottom).clampTo(image)
    }

    /**
     * Manual reference crops never become extremely thin. Correct the aspect only by EXPANDING the
     * crop, never by trimming a dimension that already contains the subjects. Extra room goes toward
     * whichever side of the source actually has room, so edge subjects remain off-centre.
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

    /** Allocate added size in proportion to the free source space, not around the crop centre. */
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
