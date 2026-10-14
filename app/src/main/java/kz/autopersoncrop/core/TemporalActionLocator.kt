package kz.autopersoncrop.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A tiny, in-memory preview. No frame, face or motion data is persisted or uploaded. */
data class MotionFrame(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size == width * height) }

    companion object {
        fun fromArgb(previewSize: Int, previewHeight: Int, image: Bitmap): MotionFrame {
            val scaled = Bitmap.createScaledBitmap(image, previewSize, previewHeight, true)
            return try {
                val data = IntArray(previewSize * previewHeight)
                scaled.getPixels(data, 0, previewSize, 0, 0, previewSize, previewHeight)
                MotionFrame(previewSize, previewHeight, data)
            } finally {
                if (scaled !== image && !scaled.isRecycled) scaled.recycle()
            }
        }
    }
}

data class TemporalFocus(val rect: RectD, val supportingFrames: Int)

/**
 * Conservative temporal cue used to rank YOLO *people*, not to assert that motion is human.
 * A moving camera or a scene cut produces broad differences and is rejected. Motion cannot
 * independently authorize a crop: the selected main people must still have YOLO boxes.
 */
object TemporalActionLocator {
    fun locate(frame: MotionFrame, neighbors: List<MotionFrame>): TemporalFocus? {
        val proposals = neighbors.take(2).mapNotNull { previous ->
            if (previous.width != frame.width || previous.height != frame.height) null
            else proposal(frame, previous)
        }
        if (proposals.isEmpty()) return null
        if (proposals.size == 1) return TemporalFocus(proposals.first().first, 1)
        val first = proposals[0].first
        val second = proposals[1].first
        val overlap = intersectionFraction(first, second)
        return if (overlap >= 0.20) {
            TemporalFocus(
                RectD(
                    min(first.left, second.left), min(first.top, second.top),
                    max(first.right, second.right), max(first.bottom, second.bottom),
                ).clamp(frame.width.toDouble(), frame.height.toDouble()), 2,
            )
        } else {
            // Contradictory motion regions are not reliable enough to override a person detector.
            null
        }
    }

    private fun proposal(a: MotionFrame, b: MotionFrame): Pair<RectD, Double>? {
        // Compensate modest hand-held camera translation before foreground subtraction.
        val w = a.width
        val h = a.height
        var bestX = 0
        var bestY = 0
        var smallest = Double.POSITIVE_INFINITY
        for (dy in -2..2) for (dx in -2..2) {
            var sum = 0L
            var count = 0
            for (y in 2 until h - 2 step 3) for (x in 2 until w - 2 step 3) {
                val px = x + dx
                val py = y + dy
                sum += colorDifference(a.argb[y * w + x], b.argb[py * w + px]).toLong()
                count++
            }
            val avg = sum.toDouble() / max(count, 1)
            if (avg < smallest) { smallest = avg; bestX = dx; bestY = dy }
        }
        // Global motion means scene cut, zoom or camera swing, not a stable subject cue.
        if (smallest > 35.0) return null
        val cellW = 4
        val cellH = 3
        val gridW = w / cellW
        val gridH = h / cellH
        val energy = DoubleArray(gridW * gridH)
        var highMotion = 0
        var compared = 0
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val d = colorDifference(
                a.argb[y * w + x],
                b.argb[(y + bestY) * w + x + bestX],
            )
            compared++
            if (d >= 38) {
                highMotion++
                energy[(y / cellH) * gridW + (x / cellW)] += 1.0
            }
        }
        val fraction = highMotion.toDouble() / max(1, compared)
        if (fraction < 0.012 || fraction > 0.42) return null

        val active = BooleanArray(energy.size) { i -> energy[i] >= 2.0 }
        val visited = BooleanArray(active.size)
        // A moving body often leaves TWO disjoint difference silhouettes (old and new
        // positions). Merge nearby components on the same action plane before selecting
        // a focus; otherwise the crop would lock onto a hand or a single shoe.
        data class Component(val bounds: RectD, val energy: Double, val cells: Int)
        val components = mutableListOf<Component>()
        for (sy in 0 until gridH) for (sx in 0 until gridW) {
            val start = sy * gridW + sx
            if (!active[start] || visited[start]) continue
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            var minX = sx
            var minY = sy
            var maxX = sx
            var maxY = sy
            var cells = 0
            var totalEnergy = 0.0
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % gridW
                val y = index / gridW
                cells++
                totalEnergy += energy[index]
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                for (oy in -1..1) for (ox in -1..1) {
                    val nx = x + ox
                    val ny = y + oy
                    if (nx !in 0 until gridW || ny !in 0 until gridH) continue
                    val next = ny * gridW + nx
                    if (active[next] && !visited[next]) {
                        visited[next] = true
                        queue.add(next)
                    }
                }
            }
            val candidate = RectD(
                (minX * cellW).toDouble(), (minY * cellH).toDouble(),
                ((maxX + 1) * cellW).toDouble(), ((maxY + 1) * cellH).toDouble(),
            )
            if (cells >= 2 && totalEnergy >= 3.0) {
                components += Component(candidate, totalEnergy, cells)
            }
        }
        val ranked = components.sortedByDescending { it.energy }
        val anchor = ranked.firstOrNull() ?: return null
        var best = anchor.bounds
        var bestCells = anchor.cells
        for (part in ranked.drop(1)) {
            val other = part.bounds
            val gapX = max(0.0, max(best.left, other.left) - min(best.right, other.right))
            val gapY = max(0.0, max(best.top, other.top) - min(best.bottom, other.bottom))
            if (gapX <= w * 0.32 && gapY <= h * 0.20 &&
                abs(best.centerY - other.centerY) <= h * 0.26) {
                best = RectD(
                    min(best.left, other.left), min(best.top, other.top),
                    max(best.right, other.right), max(best.bottom, other.bottom),
                )
                bestCells += part.cells
            }
        }
        if (bestCells < 3 || best.width < w * 0.09 || best.height < h * 0.09) return null
        // Broad components usually mean panning / lighting changes, not moving wrestlers.
        if (best.width > w * 0.85 || best.height > h * 0.85) return null
        val marginX = max(best.width * 0.20, w * 0.02)
        val marginY = max(best.height * 0.20, h * 0.02)
        val expanded = RectD(
            best.left - marginX, best.top - marginY,
            best.right + marginX, best.bottom + marginY,
        ).clamp(w.toDouble(), h.toDouble())
        return expanded to bestEnergy
    }

    private fun colorDifference(a: Int, b: Int): Int =
        (abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
            abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) +
            abs((a and 255) - (b and 255))) / 3

    private fun intersectionFraction(a: RectD, b: RectD): Double {
        val dx = max(0.0, min(a.right, b.right) - max(a.left, b.left))
        val dy = max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))
        return dx * dy / min(a.area, b.area).coerceAtLeast(1.0)
    }

    private fun RectD.clamp(w: Double, h: Double) = RectD(
        left.coerceIn(0.0, w), top.coerceIn(0.0, h),
        right.coerceIn(0.0, w), bottom.coerceIn(0.0, h),
    )
}
