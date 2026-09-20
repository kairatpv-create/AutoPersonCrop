package kz.autopersoncrop.ml

import android.graphics.Bitmap
import kz.autopersoncrop.core.RectD

interface PersonDetector : AutoCloseable {
    /** Returns person boxes in the coordinate system of [bitmap]. */
    fun detect(bitmap: Bitmap): List<RectD>

    /**
     * Recovery pass used only when the normal pass found nobody.
     * Implementations may use a lower confidence threshold without changing the normal path.
     */
    fun detect(bitmap: Bitmap, minConfidence: Float): List<RectD> = detect(bitmap)
}
