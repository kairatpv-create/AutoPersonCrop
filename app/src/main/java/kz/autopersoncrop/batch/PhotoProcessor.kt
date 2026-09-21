package kz.autopersoncrop.batch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kz.autopersoncrop.core.ImageSize
import kz.autopersoncrop.core.PixelRect
import kz.autopersoncrop.core.RectD
import kz.autopersoncrop.core.WrestlingCropPlanner
import kz.autopersoncrop.core.WrestlingSubjectSelector
import kz.autopersoncrop.io.ImageFrameLoader
import kz.autopersoncrop.io.PhotoFrame
import kz.autopersoncrop.io.SourcePhoto
import kz.autopersoncrop.jpeg.ExifCropMapper
import kz.autopersoncrop.jpeg.LosslessJpegTransformer
import kz.autopersoncrop.ml.PersonDetector
import kz.autopersoncrop.settings.OutputSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed class ProcessResult {
    data object Cropped : ProcessResult()
    data object CopiedFull : ProcessResult()
    data object NoPeopleCopied : ProcessResult()
    data object AlreadyExists : ProcessResult()
}

/**
 * Wrestling-oriented one/two-person processor.
 *
 * 0.6.10 removes automatic 0/90/180/270 image correction. Detection and crop planning always work
 * in the photo's normal EXIF-displayed orientation. Several recent successful frames are retained
 * so one unstable YOLO result does not break a sequence. Scene similarity deliberately weights the
 * stable outer background more than the fast moving wrestling action in the centre.
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

    private data class SequenceReference(
        val relativeDir: String,
        val uprightWidth: Int,
        val uprightHeight: Int,
        val signature: IntArray,
        val normalizedSubjects: List<RectD>,
    )

    private val sequenceReferences = ArrayList<SequenceReference>()
    private var sequenceFallbackStreak = 0
    private var activeRelativeDir: String? = null

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
        if (activeRelativeDir != photo.relativeDir) {
            activeRelativeDir = photo.relativeDir
            sequenceReferences.clear()
            sequenceFallbackStreak = 0
        }

        // Never auto-rotate the photo. ImageFrameLoader only applies the source EXIF orientation so
        // detection sees the same upright view as the user sees in the gallery.
        val workingPreview = frame.preview
        val signature = frameSignature(workingPreview)
        val previewWidth = workingPreview.width
        val previewHeight = workingPreview.height
        val imageSize = ImageSize(frame.uprightWidth, frame.uprightHeight)

        val previewBoxes = try {
            detectPeopleWithRecovery(workingPreview)
        } finally {
            if (!frame.preview.isRecycled) frame.preview.recycle()
        }

        val sx = frame.uprightWidth.toDouble() / previewWidth.coerceAtLeast(1)
        val sy = frame.uprightHeight.toDouble() / previewHeight.coerceAtLeast(1)
        var detectedSubjects: List<RectD>? = null
        var usedSequenceFallback = false

        val subjectsForCrop: List<RectD> = if (previewBoxes.isNotEmpty()) {
            val fullBoxes = previewBoxes.map { b ->
                RectD(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
            }
            val selected = WrestlingSubjectSelector.select(imageSize, fullBoxes)
            detectedSubjects = selected

            // When one detected person is complete and another box is already clipped by the source
            // boundary, the single-person choice is intentional. Never reintroduce the clipped person
            // from sequence memory.
            val intentionalFullPersonFocus = selected.size == 1 &&
                WrestlingSubjectSelector.isFullyVisible(imageSize, selected.first()) &&
                fullBoxes.any { !WrestlingSubjectSelector.isFullyVisible(imageSize, it) }

            val assisted = if (selected.size == 1 && !intentionalFullPersonFocus) {
                sequenceAssistForPartialDetection(photo, imageSize, signature, selected)
            } else null

            if (assisted != null) {
                usedSequenceFallback = true
                assisted
            } else {
                selected
            }
        } else {
            val carried = sequenceFallbackForMiss(photo, imageSize, signature)
            if (carried == null) {
                sequenceFallbackStreak = 0
                copyExact(photo, outputDir)
                return ProcessResult.NoPeopleCopied
            }
            usedSequenceFallback = true
            carried
        }

        // Selected wrestler geometry is final. The crop stage never re-selects people and never
        // rotates the image.
        val uprightCrop = WrestlingCropPlanner.plan(imageSize, subjectsForCrop)
        val raw = ExifCropMapper.uprightToRaw(
            uprightCrop,
            frame.rawWidth,
            frame.rawHeight,
            frame.exifOrientation,
        )

        val result = if (outputSettings.strictLossless) {
            if (isFull(raw, frame.rawWidth, frame.rawHeight)) {
                copyExact(photo, outputDir)
                ProcessResult.CopiedFull
            } else {
                transformer.crop(photo.uri, outputDir, photo.name, raw)
                ProcessResult.Cropped
            }
        } else {
            encodeConfigured(photo, outputDir, raw, frame)
            if (isFull(raw, frame.rawWidth, frame.rawHeight)) ProcessResult.CopiedFull else ProcessResult.Cropped
        }

        if (!usedSequenceFallback && !detectedSubjects.isNullOrEmpty()) {
            rememberSequenceReference(
                photo = photo,
                image = imageSize,
                signature = signature,
                subjects = detectedSubjects!!,
            )
            sequenceFallbackStreak = 0
        } else if (usedSequenceFallback) {
            sequenceFallbackStreak++
        }

        return result
    }

    private fun rememberSequenceReference(
        photo: SourcePhoto,
        image: ImageSize,
        signature: IntArray,
        subjects: List<RectD>,
    ) {
        val w = image.width.toDouble().coerceAtLeast(1.0)
        val h = image.height.toDouble().coerceAtLeast(1.0)
        val ref = SequenceReference(
            relativeDir = photo.relativeDir,
            uprightWidth = image.width,
            uprightHeight = image.height,
            signature = signature.copyOf(),
            normalizedSubjects = subjects.map { r ->
                RectD(r.left / w, r.top / h, r.right / w, r.bottom / h)
            },
        )
        sequenceReferences.add(0, ref)
        while (sequenceReferences.size > MAX_SEQUENCE_REFERENCES) {
            sequenceReferences.removeAt(sequenceReferences.lastIndex)
        }
    }

    private fun sequenceFallbackForMiss(
        photo: SourcePhoto,
        image: ImageSize,
        signature: IntArray,
    ): List<RectD>? {
        if (sequenceFallbackStreak >= MAX_CONSECUTIVE_SEQUENCE_FALLBACKS) return null
        val ref = matchingSequenceReferences(photo.relativeDir, image, signature).firstOrNull()?.first ?: return null
        val mapped = denormalize(ref.normalizedSubjects, image.width, image.height)
        if (mapped.isEmpty()) return null

        // Keep one/two-person structure from the confirmed neighbour. Collapsing two people into one
        // wide union made two standing people look like a lying subject and forced landscape crops.
        return mapped.map { expandRect(it, image.width, image.height, 0.16) }
    }

    private fun sequenceAssistForPartialDetection(
        photo: SourcePhoto,
        image: ImageSize,
        signature: IntArray,
        current: List<RectD>,
    ): List<RectD>? {
        if (sequenceFallbackStreak >= MAX_CONSECUTIVE_SEQUENCE_FALLBACKS) return null
        val ref = matchingSequenceReferences(photo.relativeDir, image, signature)
            .firstOrNull { it.first.normalizedSubjects.size >= 2 }
            ?.first ?: return null

        val previous = denormalize(ref.normalizedSubjects, image.width, image.height)
        if (previous.size < 2 || current.isEmpty()) return null

        // Replace the nearest remembered subject by the fresh detection, while preserving the second
        // remembered wrestler. This keeps the pair geometry without turning it into one broad box.
        val fresh = current.first()
        val nearestIndex = previous.indices.minByOrNull { centerDistance(previous[it], fresh, image) } ?: return null
        val assisted = previous.toMutableList()
        assisted[nearestIndex] = expandedUnion(
            listOf(previous[nearestIndex], fresh),
            image.width,
            image.height,
            0.08,
        )
        return assisted.map { expandRect(it, image.width, image.height, 0.08) }
    }

    private fun centerDistance(a: RectD, b: RectD, image: ImageSize): Double {
        val dx = (a.centerX - b.centerX) / image.width.coerceAtLeast(1).toDouble()
        val dy = (a.centerY - b.centerY) / image.height.coerceAtLeast(1).toDouble()
        return dx * dx + dy * dy
    }

    private fun matchingSequenceReferences(
        relativeDir: String,
        image: ImageSize,
        signature: IntArray,
    ): List<Pair<SequenceReference, Double>> = sequenceReferences.asSequence()
        .filter {
            it.relativeDir == relativeDir &&
                it.uprightWidth == image.width &&
                it.uprightHeight == image.height
        }
        .map { it to signatureDistance(it.signature, signature) }
        .filter { it.second <= MAX_SEQUENCE_DISTANCE }
        .sortedBy { it.second }
        .toList()

    /**
     * Exposure-tolerant scene distance. Outer/background cells have more weight than the centre,
     * because in wrestling bursts the athletes can move sharply while the hall/mat/background stays
     * nearly unchanged.
     */
    private fun signatureDistance(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return Double.POSITIVE_INFINITY
        val meanA = a.average()
        val meanB = b.average()
        val brightnessShift = abs(meanA - meanB)
        if (brightnessShift > MAX_BRIGHTNESS_SHIFT) return Double.POSITIVE_INFINITY

        var weightedDiff = 0.0
        var totalWeight = 0.0
        var outerOutliers = 0
        var outerCount = 0
        val grid = SIGNATURE_GRID
        for (i in a.indices) {
            val x = i % grid
            val y = i / grid
            val central = x in (grid / 4) until (grid - grid / 4) &&
                y in (grid / 4) until (grid - grid / 4)
            val weight = if (central) 0.38 else 1.0
            val centeredDiff = abs((a[i] - meanA) - (b[i] - meanB))
            weightedDiff += centeredDiff * weight
            totalWeight += weight
            if (!central) {
                outerCount++
                if (centeredDiff > SIGNATURE_OUTLIER_DIFF) outerOutliers++
            }
        }
        val meanDiff = weightedDiff / totalWeight.coerceAtLeast(1.0)
        val outlierFraction = outerOutliers.toDouble() / outerCount.coerceAtLeast(1)
        return meanDiff + outlierFraction * 18.0 + brightnessShift * 0.06
    }

    private fun frameSignature(bitmap: Bitmap): IntArray {
        val grid = SIGNATURE_GRID
        val values = IntArray(grid * grid)
        var index = 0
        for (gy in 0 until grid) {
            val y = (((gy + 0.5) * bitmap.height) / grid).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until grid) {
                val x = (((gx + 0.5) * bitmap.width) / grid).toInt().coerceIn(0, bitmap.width - 1)
                val c = bitmap.getPixel(x, y)
                values[index++] = (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8
            }
        }
        return values
    }

    private fun denormalize(normalized: List<RectD>, width: Int, height: Int): List<RectD> =
        normalized.map { r ->
            RectD(
                r.left * width,
                r.top * height,
                r.right * width,
                r.bottom * height,
            ).clampTo(width, height)
        }.filter { it.width >= 2.0 && it.height >= 2.0 }

    private fun expandRect(rect: RectD, width: Int, height: Int, fraction: Double): RectD {
        val mx = max(rect.width * fraction, width * 0.010)
        val my = max(rect.height * fraction, height * 0.010)
        return RectD(rect.left - mx, rect.top - my, rect.right + mx, rect.bottom + my).clampTo(width, height)
    }

    private fun expandedUnion(rects: List<RectD>, width: Int, height: Int, fraction: Double): RectD {
        val u = RectD(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom },
        )
        val mx = max(u.width * fraction, width * 0.020)
        val my = max(u.height * fraction, height * 0.020)
        return RectD(u.left - mx, u.top - my, u.right + mx, u.bottom + my).clampTo(width, height)
    }

    private fun RectD.clampTo(width: Int, height: Int): RectD = RectD(
        left.coerceIn(0.0, width.toDouble()),
        top.coerceIn(0.0, height.toDouble()),
        right.coerceIn(0.0, width.toDouble()),
        bottom.coerceIn(0.0, height.toDouble()),
    )

    /**
     * Normal detector + softer whole-frame pass + overlapping tiles. The detector itself also has
     * wrestling-specific recovery views. A zero result therefore means several independent passes
     * have failed before sequence recovery is considered.
     */
    private fun detectPeopleWithRecovery(preview: Bitmap): List<RectD> {
        val image = ImageSize(preview.width, preview.height)
        val normal = deduplicate(detector.detect(preview))
        if (normal.isNotEmpty()) {
            val selected = runCatching { WrestlingSubjectSelector.select(image, normal) }.getOrDefault(emptyList())
            if (selected.size >= 2) return normal
        }

        val combined = ArrayList<RectD>(normal)
        combined += detector.detect(preview, RECOVERY_CONFIDENCE)
            .filter { isUsefulRecoveryBox(it, preview) }

        val afterSoft = deduplicate(combined)
        if (afterSoft.isNotEmpty()) {
            val selected = runCatching { WrestlingSubjectSelector.select(image, afterSoft) }.getOrDefault(emptyList())
            if (selected.size >= 2) return afterSoft
        }

        combined += detectInOverlappingTiles(preview)
        return deduplicate(combined)
    }

    private fun detectInOverlappingTiles(preview: Bitmap): List<RectD> {
        val horizontalSplit = preview.width >= preview.height
        val longSide = if (horizontalSplit) preview.width else preview.height
        if (longSide < 420) return emptyList()

        val tileLong = (longSide * 0.74).roundToInt().coerceIn(1, longSide)
        val end = longSide - tileLong
        val offsets = intArrayOf(0, end / 2, end).distinct()
        val found = ArrayList<RectD>()

        for (offset in offsets) {
            val tile = if (horizontalSplit) {
                Bitmap.createBitmap(preview, offset, 0, tileLong, preview.height)
            } else {
                Bitmap.createBitmap(preview, 0, offset, preview.width, tileLong)
            }
            try {
                val boxes = detector.detect(tile, TILE_RECOVERY_CONFIDENCE)
                for (box in boxes) {
                    val mapped = if (horizontalSplit) {
                        RectD(box.left + offset, box.top, box.right + offset, box.bottom)
                    } else {
                        RectD(box.left, box.top + offset, box.right, box.bottom + offset)
                    }
                    if (isUsefulRecoveryBox(mapped, preview)) found += mapped
                }
            } finally {
                if (!tile.isRecycled) tile.recycle()
            }
        }
        return deduplicate(found)
    }

    private fun isUsefulRecoveryBox(box: RectD, preview: Bitmap): Boolean {
        if (box.width < 5.0 || box.height < 5.0) return false
        val imageArea = preview.width.toDouble() * preview.height.toDouble()
        return box.area / imageArea.coerceAtLeast(1.0) >= MIN_RECOVERY_AREA_FRACTION
    }

    /** Preserve strongly overlapping wrestlers; remove only very similar cross-pass duplicates. */
    private fun deduplicate(input: List<RectD>): List<RectD> {
        if (input.size <= 1) return input
        val sorted = input.sortedByDescending { it.area }
        val keep = ArrayList<RectD>()
        for (candidate in sorted) {
            if (keep.none { nearDuplicate(it, candidate) }) keep += candidate
        }
        return keep.take(MAX_CANDIDATES)
    }

    private fun nearDuplicate(a: RectD, b: RectD): Boolean {
        val iou = overlapIoU(a, b)
        if (iou < 0.86) return false
        val areaRatio = min(a.area, b.area) / max(a.area, b.area).coerceAtLeast(1.0)
        if (areaRatio < 0.78) return false
        val dx = abs(a.centerX - b.centerX) / max(a.width, b.width).coerceAtLeast(1.0)
        val dy = abs(a.centerY - b.centerY) / max(a.height, b.height).coerceAtLeast(1.0)
        return dx <= 0.10 && dy <= 0.10
    }

    private fun overlapIoU(a: RectD, b: RectD): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }

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
            runCatching { out.delete() }
            throw t
        }
    }

    private fun isFull(r: PixelRect, w: Int, h: Int) =
        r.left == 0 && r.top == 0 && r.right == w && r.bottom == h

    companion object {
        private const val RECOVERY_CONFIDENCE = 0.095f
        private const val TILE_RECOVERY_CONFIDENCE = 0.080f
        private const val MIN_RECOVERY_AREA_FRACTION = 0.0025
        private const val MAX_CANDIDATES = 14

        private const val SIGNATURE_GRID = 16
        private const val MAX_BRIGHTNESS_SHIFT = 55.0
        private const val SIGNATURE_OUTLIER_DIFF = 54.0
        private const val MAX_SEQUENCE_DISTANCE = 29.0
        private const val MAX_SEQUENCE_REFERENCES = 4
        private const val MAX_CONSECUTIVE_SEQUENCE_FALLBACKS = 5
    }
}
