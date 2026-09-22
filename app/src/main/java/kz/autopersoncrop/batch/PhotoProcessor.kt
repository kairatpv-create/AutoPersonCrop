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
import kz.autopersoncrop.core.SubjectLayout
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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        val layout: SubjectLayout,
    )

    private data class SetMatch(val subjects: List<RectD>, val score: Double)

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

        val workingPreview = frame.preview
        val signature = frameSignature(workingPreview)
        val previewWidth = workingPreview.width
        val previewHeight = workingPreview.height
        val imageSize = ImageSize(frame.uprightWidth, frame.uprightHeight)
        val history = matchingSequenceReferences(photo.relativeDir, imageSize, signature)
        val expectedFull = consensusSubjects(history, imageSize)
        val expectedPreview = expectedFull?.map { r ->
            RectD(
                r.left * previewWidth / imageSize.width.toDouble(),
                r.top * previewHeight / imageSize.height.toDouble(),
                r.right * previewWidth / imageSize.width.toDouble(),
                r.bottom * previewHeight / imageSize.height.toDouble(),
            )
        }

        val previewBoxes = try {
            detectPeopleWithRecovery(workingPreview, expectedPreview)
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
            val genericSelected = WrestlingSubjectSelector.select(imageSize, fullBoxes)
            val intentionalFullPersonFocus = genericSelected.size == 1 &&
                WrestlingSubjectSelector.isFullyVisible(imageSize, genericSelected.first()) &&
                fullBoxes.any { box ->
                    !WrestlingSubjectSelector.isFullyVisible(imageSize, box) &&
                        normalizedGap(genericSelected.first(), box, imageSize) <= 0.13
                }

            val guided = if (!intentionalFullPersonFocus) {
                sequenceGuidedSelection(imageSize, fullBoxes, genericSelected, history)
            } else null
            val selectedCurrent = guided ?: genericSelected
            detectedSubjects = selectedCurrent

            if (intentionalFullPersonFocus) {
                selectedCurrent
            } else {
                val assisted = if (selectedCurrent.size == 1) {
                    sequenceAssistForPartialDetection(imageSize, history, selectedCurrent)
                } else null

                if (assisted != null) {
                    usedSequenceFallback = true
                    assisted
                } else {
                    val expected = consensusSubjects(history, imageSize)
                    val currentDistance = if (expected != null) {
                        setDistance(selectedCurrent, expected, imageSize)
                    } else Double.POSITIVE_INFINITY
                    val carried = if (
                        expected != null &&
                        history.size >= 2 &&
                        currentDistance > OUTLIER_CURRENT_DISTANCE
                    ) {
                        sequenceFallbackForMiss(imageSize, history)
                    } else null

                    if (carried != null) {
                        usedSequenceFallback = true
                        carried
                    } else selectedCurrent
                }
            }
        } else {
            val carried = sequenceFallbackForMiss(imageSize, history)
            if (carried == null) {
                sequenceFallbackStreak = 0
                copyExact(photo, outputDir)
                return ProcessResult.NoPeopleCopied
            }
            usedSequenceFallback = true
            carried
        }

        val currentLayout = WrestlingCropPlanner.classifyLayout(subjectsForCrop)
        val rememberedLayout = consensusLayout(history)
        val preferredLayout = if (
            rememberedLayout != null &&
            rememberedLayout != currentLayout &&
            !WrestlingCropPlanner.hasStrongLayoutEvidence(subjectsForCrop, currentLayout)
        ) rememberedLayout else null
        val effectiveLayout = preferredLayout ?: currentLayout

        val uprightCrop = WrestlingCropPlanner.plan(
            image = imageSize,
            subjects = subjectsForCrop,
            preferredLayout = preferredLayout,
        )
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
                layout = effectiveLayout,
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
        layout: SubjectLayout,
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
            layout = layout,
        )
        sequenceReferences.add(0, ref)
        while (sequenceReferences.size > MAX_SEQUENCE_REFERENCES) {
            sequenceReferences.removeAt(sequenceReferences.lastIndex)
        }
    }

    private fun sequenceGuidedSelection(
        image: ImageSize,
        fullBoxes: List<RectD>,
        generic: List<RectD>,
        history: List<Pair<SequenceReference, Double>>,
    ): List<RectD>? {
        val expected = consensusSubjects(history, image) ?: return null
        val best = bestDetectionSet(fullBoxes, expected, image) ?: return null
        val threshold = if (expected.size == 1) MAX_TRACK_SINGLE_DISTANCE else MAX_TRACK_PAIR_DISTANCE
        if (best.score > threshold) return null
        val genericScore = setDistance(generic, expected, image)
        return if (
            generic.size != expected.size ||
            !genericScore.isFinite() ||
            genericScore > best.score + TRACK_OVERRIDE_ADVANTAGE
        ) best.subjects else null
    }

    private fun sequenceFallbackForMiss(
        image: ImageSize,
        history: List<Pair<SequenceReference, Double>>,
    ): List<RectD>? {
        if (history.isEmpty()) return null
        if (sequenceFallbackStreak >= MAX_CONSECUTIVE_SEQUENCE_FALLBACKS) return null
        if (history.size == 1) {
            if (history.first().second > SINGLE_REFERENCE_MAX_DISTANCE) return null
            if (sequenceFallbackStreak >= MAX_SINGLE_REFERENCE_FALLBACKS) return null
        }
        val consensus = consensusSubjects(history, image) ?: return null
        return consensus.map { expandRect(it, image.width, image.height, 0.12) }
    }

    private fun sequenceAssistForPartialDetection(
        image: ImageSize,
        history: List<Pair<SequenceReference, Double>>,
        current: List<RectD>,
    ): List<RectD>? {
        if (current.isEmpty()) return null
        val expected = consensusSubjects(history, image) ?: return null
        if (expected.size < 2) return null
        val fresh = current.first()
        val nearestIndex = expected.indices.minByOrNull { trackDistance(expected[it], fresh, image) } ?: return null
        val nearestDistance = trackDistance(expected[nearestIndex], fresh, image)
        if (nearestDistance > PARTIAL_ASSIST_MAX_DISTANCE) return null
        val assisted = expected.toMutableList()
        assisted[nearestIndex] = blendRect(expected[nearestIndex], fresh, 0.72)
        return assisted.map { expandRect(it, image.width, image.height, 0.07) }
    }

    private fun blendRect(old: RectD, fresh: RectD, freshWeight: Double): RectD {
        val fw = freshWeight.coerceIn(0.0, 1.0)
        val ow = 1.0 - fw
        return RectD(
            old.left * ow + fresh.left * fw,
            old.top * ow + fresh.top * fw,
            old.right * ow + fresh.right * fw,
            old.bottom * ow + fresh.bottom * fw,
        )
    }

    private fun consensusSubjects(
        history: List<Pair<SequenceReference, Double>>,
        image: ImageSize,
    ): List<RectD>? {
        if (history.isEmpty()) return null
        val recent = history.take(CONSENSUS_REFERENCE_LIMIT)
        val oneCount = recent.count { it.first.normalizedSubjects.size == 1 }
        val twoCount = recent.count { it.first.normalizedSubjects.size >= 2 }
        val desiredCount = when {
            twoCount > oneCount -> 2
            oneCount > twoCount -> 1
            recent.first().first.normalizedSubjects.size >= 2 -> 2
            else -> 1
        }
        val mapped = recent.asSequence()
            .map { it.first }
            .filter { if (desiredCount == 2) it.normalizedSubjects.size >= 2 else it.normalizedSubjects.size == 1 }
            .take(CONSENSUS_REFERENCE_LIMIT)
            .map { ref ->
                val boxes = denormalize(ref.normalizedSubjects.take(desiredCount), image.width, image.height)
                if (desiredCount == 2) boxes.sortedBy { it.centerX } else boxes
            }
            .filter { it.size == desiredCount }
            .toList()
        if (mapped.isEmpty()) return null
        return (0 until desiredCount).map { index ->
            RectD(
                median(mapped.map { it[index].left }),
                median(mapped.map { it[index].top }),
                median(mapped.map { it[index].right }),
                median(mapped.map { it[index].bottom }),
            ).clampTo(image.width, image.height)
        }.filter { it.width >= 2.0 && it.height >= 2.0 }
            .takeIf { it.size == desiredCount }
    }

    private fun consensusLayout(history: List<Pair<SequenceReference, Double>>): SubjectLayout? {
        val recent = history.take(ORIENTATION_REFERENCE_LIMIT)
        if (recent.size < 2) return null
        val portrait = recent.count { it.first.layout == SubjectLayout.PORTRAIT }
        val landscape = recent.size - portrait
        val winner = if (portrait >= landscape) SubjectLayout.PORTRAIT else SubjectLayout.LANDSCAPE
        val count = max(portrait, landscape)
        return winner.takeIf { count >= 2 && count.toDouble() / recent.size >= 0.66 }
    }

    private fun bestDetectionSet(
        candidates: List<RectD>,
        expected: List<RectD>,
        image: ImageSize,
    ): SetMatch? {
        val usable = candidates.filter { it.width >= 3.0 && it.height >= 3.0 && it.area >= 9.0 }
        if (usable.isEmpty() || expected.isEmpty()) return null
        if (expected.size == 1) {
            val best = usable.minByOrNull { trackDistance(it, expected.first(), image) } ?: return null
            return SetMatch(listOf(best), trackDistance(best, expected.first(), image))
        }
        if (usable.size < 2) return null
        var bestMatch: SetMatch? = null
        for (i in 0 until usable.lastIndex) {
            for (j in i + 1 until usable.size) {
                val a = usable[i]
                val b = usable[j]
                val direct = (trackDistance(a, expected[0], image) + trackDistance(b, expected[1], image)) / 2.0
                val swapped = (trackDistance(a, expected[1], image) + trackDistance(b, expected[0], image)) / 2.0
                val match = if (direct <= swapped) SetMatch(listOf(a, b), direct) else SetMatch(listOf(b, a), swapped)
                if (bestMatch == null || match.score < bestMatch!!.score) bestMatch = match
            }
        }
        return bestMatch
    }

    private fun setDistance(actual: List<RectD>, expected: List<RectD>, image: ImageSize): Double {
        if (actual.size != expected.size || actual.isEmpty()) return Double.POSITIVE_INFINITY
        if (actual.size == 1) return trackDistance(actual.first(), expected.first(), image)
        val direct = (trackDistance(actual[0], expected[0], image) + trackDistance(actual[1], expected[1], image)) / 2.0
        val swapped = (trackDistance(actual[0], expected[1], image) + trackDistance(actual[1], expected[0], image)) / 2.0
        return min(direct, swapped)
    }

    private fun trackDistance(a: RectD, b: RectD, image: ImageSize): Double {
        val dx = (a.centerX - b.centerX) / image.width.coerceAtLeast(1).toDouble()
        val dy = (a.centerY - b.centerY) / image.height.coerceAtLeast(1).toDouble()
        val center = sqrt(dx * dx + dy * dy)
        val areaRatio = (a.area.coerceAtLeast(1.0) / b.area.coerceAtLeast(1.0)).coerceIn(0.05, 20.0)
        val aspectA = (a.width / a.height.coerceAtLeast(1.0)).coerceAtLeast(0.05)
        val aspectB = (b.width / b.height.coerceAtLeast(1.0)).coerceAtLeast(0.05)
        return center + abs(ln(areaRatio)) * 0.075 + abs(ln(aspectA / aspectB)) * 0.035
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
        .take(MAX_SEQUENCE_REFERENCES)
        .toList()

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
            val weight = if (central) 0.34 else 1.0
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

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun denormalize(normalized: List<RectD>, width: Int, height: Int): List<RectD> =
        normalized.map { r ->
            RectD(r.left * width, r.top * height, r.right * width, r.bottom * height).clampTo(width, height)
        }.filter { it.width >= 2.0 && it.height >= 2.0 }

    private fun expandRect(rect: RectD, width: Int, height: Int, fraction: Double): RectD {
        val mx = max(rect.width * fraction, width * 0.010)
        val my = max(rect.height * fraction, height * 0.010)
        return RectD(rect.left - mx, rect.top - my, rect.right + mx, rect.bottom + my).clampTo(width, height)
    }

    private fun RectD.clampTo(width: Int, height: Int): RectD = RectD(
        left.coerceIn(0.0, width.toDouble()),
        top.coerceIn(0.0, height.toDouble()),
        right.coerceIn(0.0, width.toDouble()),
        bottom.coerceIn(0.0, height.toDouble()),
    )

    private fun normalizedGap(a: RectD, b: RectD, image: ImageSize): Double {
        val horizontal = when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0.0
        } / image.width.toDouble()
        val vertical = when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0.0
        } / image.height.toDouble()
        return sqrt(horizontal * horizontal + vertical * vertical)
    }

    private fun detectPeopleWithRecovery(preview: Bitmap, expectedSubjects: List<RectD>?): List<RectD> {
        val image = ImageSize(preview.width, preview.height)
        val combined = ArrayList<RectD>()
        combined += detector.detect(preview)
        combined += detector.detect(preview, RECOVERY_CONFIDENCE)
            .filter { isUsefulRecoveryBox(it, preview) }
        var recovered = deduplicate(combined)
        val needTiles = if (expectedSubjects != null && expectedSubjects.isNotEmpty()) {
            val match = bestDetectionSet(recovered, expectedSubjects, image)
            match == null || match.score > TILE_TRIGGER_TRACK_DISTANCE
        } else {
            val selected = if (recovered.isNotEmpty()) {
                runCatching { WrestlingSubjectSelector.select(image, recovered) }.getOrDefault(emptyList())
            } else emptyList()
            selected.size < 2
        }
        if (needTiles) {
            combined += detectInOverlappingTiles(preview)
            recovered = deduplicate(combined)
        }
        return recovered
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
        private const val MAX_CANDIDATES = 16
        private const val SIGNATURE_GRID = 16
        private const val MAX_BRIGHTNESS_SHIFT = 55.0
        private const val SIGNATURE_OUTLIER_DIFF = 54.0
        private const val MAX_SEQUENCE_DISTANCE = 35.0
        private const val MAX_SEQUENCE_REFERENCES = 8
        private const val CONSENSUS_REFERENCE_LIMIT = 5
        private const val ORIENTATION_REFERENCE_LIMIT = 5
        private const val MAX_CONSECUTIVE_SEQUENCE_FALLBACKS = 24
        private const val MAX_SINGLE_REFERENCE_FALLBACKS = 3
        private const val SINGLE_REFERENCE_MAX_DISTANCE = 24.0
        private const val MAX_TRACK_SINGLE_DISTANCE = 0.42
        private const val MAX_TRACK_PAIR_DISTANCE = 0.50
        private const val TRACK_OVERRIDE_ADVANTAGE = 0.09
        private const val PARTIAL_ASSIST_MAX_DISTANCE = 0.40
        private const val OUTLIER_CURRENT_DISTANCE = 0.46
        private const val TILE_TRIGGER_TRACK_DISTANCE = 0.34
    }
}
