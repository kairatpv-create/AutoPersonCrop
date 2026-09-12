package kz.autopersoncrop.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

data class PhotoFrame(
    val preview: Bitmap,
    val rawWidth: Int,
    val rawHeight: Int,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val exifOrientation: Int,
)

class ImageFrameLoader(private val context: Context) {
    /**
     * YOLO input is ~640 px, so decoding 1600 px previews wastes CPU/memory on large phone photos.
     * 960 px keeps useful detail for person detection while substantially reducing decode/scaling work.
     */
    fun load(uri: Uri, previewMaxSide: Int = 960): PhotoFrame {
        val orientation = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть EXIF" }
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать размер изображения" }

        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > previewMaxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawPreview = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            BitmapFactory.decodeStream(input, null, opts) ?: error("Не удалось декодировать превью")
        }
        val upright = applyExif(rawPreview, orientation)
        if (upright !== rawPreview) rawPreview.recycle()

        val swaps = orientation in setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        val uw = if (swaps) bounds.outHeight else bounds.outWidth
        val uh = if (swaps) bounds.outWidth else bounds.outHeight
        return PhotoFrame(upright, bounds.outWidth, bounds.outHeight, uw, uh, orientation)
    }

    private fun applyExif(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return source
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
    }
}
