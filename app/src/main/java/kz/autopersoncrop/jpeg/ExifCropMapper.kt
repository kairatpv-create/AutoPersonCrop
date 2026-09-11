package kz.autopersoncrop.jpeg

import kz.autopersoncrop.core.PixelRect

/** Maps a crop rectangle from visually-upright coordinates back to raw JPEG coordinates. */
object ExifCropMapper {
    fun uprightToRaw(rect: PixelRect, rawWidth: Int, rawHeight: Int, orientation: Int): PixelRect {
        val points = arrayOf(
            inverse(rect.left.toDouble(), rect.top.toDouble(), rawWidth, rawHeight, orientation),
            inverse(rect.right.toDouble(), rect.top.toDouble(), rawWidth, rawHeight, orientation),
            inverse(rect.left.toDouble(), rect.bottom.toDouble(), rawWidth, rawHeight, orientation),
            inverse(rect.right.toDouble(), rect.bottom.toDouble(), rawWidth, rawHeight, orientation),
        )
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        return PixelRect(
            xs.min().toInt().coerceIn(0, rawWidth - 1),
            ys.min().toInt().coerceIn(0, rawHeight - 1),
            xs.max().toInt().coerceIn(1, rawWidth),
            ys.max().toInt().coerceIn(1, rawHeight),
        )
    }

    private fun inverse(u: Double, v: Double, w: Int, h: Int, o: Int): Pair<Double, Double> = when (o) {
        2 -> (w - u) to v
        3 -> (w - u) to (h - v)
        4 -> u to (h - v)
        5 -> v to u
        6 -> v to (h - u)
        7 -> (w - v) to (h - u)
        8 -> (w - v) to u
        else -> u to v
    }
}
