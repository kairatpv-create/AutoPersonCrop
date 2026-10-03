package kz.autopersoncrop.batch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kz.autopersoncrop.core.ImageSize
import kz.autopersoncrop.core.PixelRect
import kz.autopersoncrop.core.RectD
import kz.autopersoncrop.core.WrestlingCropPlanner
import kz.autopersoncrop.io.ImageFrameLoader
import kz.autopersoncrop.io.PhotoFrame
import kz.autopersoncrop.io.SourcePhoto
import kz.autopersoncrop.jpeg.ExifCropMapper
import kz.autopersoncrop.jpeg.LosslessJpegTransformer
import kz.autopersoncrop.ml.PersonDetector
import kz.autopersoncrop.settings.OutputSettings
import kotlin.math.max
import kotlin.math.roundToInt

sealed class ProcessResult {
    data object Cropped : ProcessResult()
    data object CopiedFull : ProcessResult()
    data object NoPeopleCopied : ProcessResult()
    data object AlreadyExists : ProcessResult()
}

/**
 * Direct processor for controlled material: every source contains only the wanted one person or two
 * wrestlers. The robust detector orders its evidence by confidence/relevance. Only the first two
 * physical person envelopes are allowed to influence crop geometry; extra detector duplicates must
 * never widen the crop and reintroduce empty background.
 *
 * There is no successful full-frame copy. A valid result must be an actual crop. If reliable person
 * geometry cannot be produced, the batch service records an ERROR instead of hiding the failure as
 * an untouched JPEG in CROP.
 */
class PhotoProcessor(
    private val context: Context,
    private val detector: PersonDetector,
    private val transformer: LosslessJpegTransformer,
    @Suppress("UNUSED_PARAMETER") screenWidth: Int,
    @Suppress("UNUSED_PARAMETER") screenHeight: Int,
    private val outputSettings: OutputSettings,
) {
    private val loader = ImageFrameLoader(context)

    fun process(
        photo: SourcePhoto,
        outputDir: DocumentFile,
        existingOutputUri: Uri? = null,
        overwriteExisting: Boolean = false,
    ): ProcessResult {
        if (existingOutputUri != null) {
            if (!overwriteExisting) return ProcessResult.AlreadyExists
            check(DocumentsContract.deleteDocument(context.contentResolver, existingOutputUri)) {
                "Не удалось заменить старый результат ${photo.name}"
            }
        }

        val frame = loader.load(photo.uri)
        val preview = frame.preview
        val previewWidth = preview.width
        val previewHeight = preview.height

        val previewSubjects = try {
            detector.detect(preview)
                .filterNot { it.left < 0.0 || it.top < 0.0 || it.right <= it.left || it.bottom <= it.top }
                .take(MAX_DETECTOR_EVIDENCE_BOXES)
        } finally {
            if (!preview.isRecycled) preview.recycle()
        }

        require(previewSubjects.isNotEmpty()) {
            "Человек не распознан ни в одном проходе; исходник не скопирован"
        }
        require(previewSubjects.size <= MAX_DETECTOR_EVIDENCE_BOXES) {
            "Слишком много областей распознавания: ${previewSubjects.size}"
        }

        val sx = frame.uprightWidth.toDouble() / previewWidth.coerceAtLeast(1)
        val sy = frame.uprightHeight.toDouble() / previewHeight.coerceAtLeast(1)
        val subjects = previewSubjects.map { b ->
            RectD(
                b.left * sx,
                b.top * sy,
                b.right * sx,
                b.bottom * sy,
            )
        }

        val imageSize = ImageSize(frame.uprightWidth, frame.uprightHeight)
        val uprightCrop = WrestlingCropPlanner.plan(imageSize, subjects)
        validateCrop(uprightCrop, imageSize, subjects, photo.name)

        val raw = ExifCropMapper.uprightToRaw(
            uprightCrop,
            frame.rawWidth,
            frame.rawHeight,
            frame.exifOrientation,
        )
        validateRawCrop(raw, frame, photo.name)

        Log.i(
            TAG,
            "${photo.name}: evidence=${subjects.size} " +
                "upright=${imageSize.width}x${imageSize.height} " +
                "crop=${uprightCrop.left},${uprightCrop.top}-${uprightCrop.right},${uprightCrop.bottom} " +
                "raw=${raw.left},${raw.top}-${raw.right},${raw.bottom}",
        )

        if (outputSettings.strictLossless) {
            transformer.crop(photo.uri, outputDir, photo.name, raw)
        } else {
            encodeConfigured(photo, outputDir, raw, frame)
        }
        return ProcessResult.Cropped
    }

    private fun validateCrop(
        crop: PixelRect,
        image: ImageSize,
        subjects: List<RectD>,
        name: String,
    ) {
        require(crop.width > 0 && crop.height > 0) { "Пустая рамка для $name" }
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height) {
            "Рамка вышла за границы $name"
        }

        val full = crop.left == 0 && crop.top == 0 && crop.right == image.width && crop.bottom == image.height
        if (full) {
            val union = union(subjects)
            val wf = union.width / image.width.toDouble()
            val hf = union.height / image.height.toDouble()
            throw IllegalStateException(
                "Рамка $name получилась полным кадром (область людей ${(wf * 100).roundToInt()}% ширины, " +
                    "${(hf * 100).roundToInt()}% высоты); исходник не скопирован"
            )
        }

        for (subject in subjects) {
            require(crop.left <= subject.left + 1.0 && crop.top <= subject.top + 1.0 &&
                crop.right >= subject.right - 1.0 && crop.bottom >= subject.bottom - 1.0) {
                "Защитная проверка: рамка $name режет распознанного человека"
            }
        }
    }

    private fun validateRawCrop(raw: PixelRect, frame: PhotoFrame, name: String) {
        require(raw.width > 0 && raw.height > 0) { "Пустая JPEG-рамка для $name" }
        require(raw.left >= 0 && raw.top >= 0 && raw.right <= frame.rawWidth && raw.bottom <= frame.rawHeight) {
            "JPEG-рамка вышла за границы $name"
        }
        require(raw.width < frame.rawWidth || raw.height < frame.rawHeight) {
            "JPEG-рамка $name равна исходнику; копирование запрещено"
        }
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        rects.minOf { it.left },
        rects.minOf { it.top },
        rects.maxOf { it.right },
        rects.maxOf { it.bottom },
    )

    private fun encodeConfigured(
        photo: SourcePhoto,
        outputDir: DocumentFile,
        raw: PixelRect,
        frame: PhotoFrame,
    ) {
        val out = outputDir.createFile("image/jpeg", photo.name)
            ?: error("Не удалось создать ${photo.name}")
        try {
            val maxSide = outputSettings.resolution.maxLongSide
            var sample = 1
            if (maxSide > 0) {
                while (max(raw.width / sample, raw.height / sample) > maxSide * 2) sample *= 2
            }

            val decoded = context.contentResolver.openInputStream(photo.uri).use { input ->
                requireNotNull(input)
                val decoder = requireNotNull(BitmapRegionDecoder.newInstance(input, false)) {
                    "Не удалось открыть JPEG для выборочного декодирования"
                }
                try {
                    decoder.decodeRegion(
                        Rect(raw.left, raw.top, raw.right, raw.bottom),
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                    ) ?: error("Не удалось декодировать область JPEG")
                } finally {
                    decoder.recycle()
                }
            }

            val upright = applyExif(decoded, frame.exifOrientation)
            if (upright !== decoded) decoded.recycle()

            val finalBitmap = resizeIfNeeded(upright, maxSide)
            if (finalBitmap !== upright) upright.recycle()

            context.contentResolver.openOutputStream(out.uri, "w").use { output ->
                requireNotNull(output)
                check(finalBitmap.compress(Bitmap.CompressFormat.JPEG, outputSettings.quality.jpegQuality, output)) {
                    "Не удалось записать JPEG"
                }
                output.flush()
            }
            finalBitmap.recycle()
            copyCommonExif(photo, out)
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        }
    }

    private fun resizeIfNeeded(src: Bitmap, maxSide: Int): Bitmap {
        if (maxSide <= 0 || max(src.width, src.height) <= maxSide) return src
        val scale = maxSide.toDouble() / max(src.width, src.height).toDouble()
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
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

    private fun copyCommonExif(photo: SourcePhoto, out: DocumentFile) {
        runCatching {
            val src = context.contentResolver.openInputStream(photo.uri).use { input ->
                requireNotNull(input)
                ExifInterface(input)
            }
            context.contentResolver.openFileDescriptor(out.uri, "rw").use { pfd ->
                requireNotNull(pfd)
                val dst = ExifInterface(pfd.fileDescriptor)
                val tags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                )
                for (tag in tags) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                dst.saveAttributes()
            }
        }
    }

    companion object {
        private const val TAG = "AutoPersonCropDirect"
        private const val MAX_DETECTOR_EVIDENCE_BOXES = 2
    }
}
