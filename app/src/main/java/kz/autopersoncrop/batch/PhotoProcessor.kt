package kz.autopersoncrop.batch

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kz.autopersoncrop.core.CropPlanner
import kz.autopersoncrop.core.ImageSize
import kz.autopersoncrop.core.PixelRect
import kz.autopersoncrop.core.RectD
import kz.autopersoncrop.io.ImageFrameLoader
import kz.autopersoncrop.io.SourcePhoto
import kz.autopersoncrop.jpeg.ExifCropMapper
import kz.autopersoncrop.jpeg.LosslessJpegTransformer
import kz.autopersoncrop.ml.PersonDetector

sealed class ProcessResult {
    data object Cropped : ProcessResult()
    data object CopiedFull : ProcessResult()
    data object NoPeopleCopied : ProcessResult()
    data object AlreadyExists : ProcessResult()
}

class PhotoProcessor(
    private val context: Context,
    private val detector: PersonDetector,
    private val transformer: LosslessJpegTransformer,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {
    private val loader = ImageFrameLoader(context)

    /**
     * Process exactly one source JPEG. Only a small preview is decoded for detection.
     * The final JPEG pixels are never decoded/re-encoded: either the source bytes are
     * copied verbatim or libjpeg-turbo performs a coefficient-domain crop.
     */
    fun process(
        photo: SourcePhoto,
        outputDir: DocumentFile,
        overwriteExisting: Boolean = false,
    ): ProcessResult {
        val existing = outputDir.findFile(photo.name)
        if (existing != null) {
            if (!overwriteExisting) return ProcessResult.AlreadyExists
            check(existing.delete()) { "Не удалось заменить старый результат ${photo.name}" }
        }

        val frame = loader.load(photo.uri)
        val previewWidth = frame.preview.width
        val previewHeight = frame.preview.height
        val previewBoxes = try {
            detector.detect(frame.preview)
        } finally {
            // Release the decoded bitmap before we read the full JPEG byte stream.
            // This matters for long batches and high-resolution phone photos.
            if (!frame.preview.isRecycled) frame.preview.recycle()
        }

        if (previewBoxes.isEmpty()) {
            copyExact(photo, outputDir)
            return ProcessResult.NoPeopleCopied
        }

        val sx = frame.uprightWidth.toDouble() / previewWidth.coerceAtLeast(1)
        val sy = frame.uprightHeight.toDouble() / previewHeight.coerceAtLeast(1)
        val fullBoxes = previewBoxes.map { b ->
            RectD(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
        }
        val plan = CropPlanner.plan(
            image = ImageSize(frame.uprightWidth, frame.uprightHeight),
            people = fullBoxes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            marginFraction = 0.05,
        )
        val raw = ExifCropMapper.uprightToRaw(
            plan.rect, frame.rawWidth, frame.rawHeight, frame.exifOrientation
        )
        if (isFull(raw, frame.rawWidth, frame.rawHeight)) {
            copyExact(photo, outputDir)
            return ProcessResult.CopiedFull
        }
        transformer.crop(photo.uri, outputDir, photo.name, raw)
        return ProcessResult.Cropped
    }

    private fun copyExact(photo: SourcePhoto, outputDir: DocumentFile) {
        val out = outputDir.createFile("image/jpeg", photo.name)
            ?: error("Не удалось создать ${photo.name}")
        try {
            context.contentResolver.openInputStream(photo.uri).use { input ->
                context.contentResolver.openOutputStream(out.uri, "w").use { output ->
                    requireNotNull(input); requireNotNull(output)
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                }
            }
        } catch (t: Throwable) {
            // Never leave a knowingly broken final file behind.
            runCatching { out.delete() }
            throw t
        }
    }

    private fun isFull(r: PixelRect, w: Int, h: Int) =
        r.left == 0 && r.top == 0 && r.right == w && r.bottom == h
}
