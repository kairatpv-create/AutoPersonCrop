package kz.autopersoncrop.ml

import android.graphics.Bitmap
import kz.autopersoncrop.core.RectD

/** One semantic object detected in the coordinate system of the supplied bitmap. */
data class DetectedObject(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val boundingBox: RectD,
)

/** Offline multi-class object detector. */
interface ObjectDetector : AutoCloseable {
    fun detect(bitmap: Bitmap): List<DetectedObject>
}
