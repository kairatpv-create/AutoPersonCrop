package kz.autopersoncrop.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.system.Os
import android.system.OsConstants
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
     * Keep enough real pixels for YOLO small-person recall. The previous power-of-two loop could
     * overshoot badly (for example 6000 px -> 375 px), then upscale that tiny preview back to the
     * model input. We now stop before the next sample would fall below the detector target.
     */
    fun load(uri: Uri, detectorTargetSide: Int = 640): PhotoFrame {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Не удалось открыть изображение")
        pfd.use {
            val fd = it.fileDescriptor
            val orientation = runCatching {
                rewind(fd)
                ExifInterface(fd).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            rewind(fd)
            BitmapFactory.decodeFileDescriptor(fd, null, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "Не удалось прочитать размер изображения"
            }

            val longest = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= detectorTargetSide) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            rewind(fd)
            val rawPreview = BitmapFactory.decodeFileDescriptor(fd, null, opts)
                ?: error("Не удалось декодировать превью")
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
    }

    private fun rewind(fd: java.io.FileDescriptor) {
        runCatching { Os.lseek(fd, 0L, OsConstants.SEEK_SET) }
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
