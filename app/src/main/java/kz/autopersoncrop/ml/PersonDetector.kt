package kz.autopersoncrop.ml

import android.graphics.Bitmap
import kz.autopersoncrop.core.RectD

interface PersonDetector : AutoCloseable {
    /** Returns person boxes in the coordinate system of [bitmap]. */
    fun detect(bitmap: Bitmap): List<RectD>
}
