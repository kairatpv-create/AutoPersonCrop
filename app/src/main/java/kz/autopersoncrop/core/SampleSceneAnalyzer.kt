package kz.autopersoncrop.core

import android.content.Context
import kz.autopersoncrop.io.ImageFrameLoader
import kz.autopersoncrop.io.SourcePhoto
import kz.autopersoncrop.ml.PersonDetector
import kotlin.math.max
import kotlin.math.min

/** Builds a robust framing profile from manually cropped photos in ОБРАЗЕЦ. */
class SampleSceneAnalyzer(
    context: Context,
    private val detector: PersonDetector,
) {
    private val loader = ImageFrameLoader(context)

    fun analyze(samplesByScene: Map<String, List<SourcePhoto>>): Map<String, SceneCropProfile> {
        val result = LinkedHashMap<String, SceneCropProfile>()
        for ((scene, samples) in samplesByScene) {
            val observations = samples
                .take(MAX_SAMPLES_PER_SCENE)
                .mapNotNull { sample -> runCatching { analyzeOne(sample) }.getOrNull() }
            if (observations.isEmpty()) continue

            val single = buildStyle(observations.filter { it.subjectCount == 1 })
            val pair = buildStyle(observations.filter { it.subjectCount >= 2 })
            val fallback = buildStyle(observations)
            result[scene] = SceneCropProfile(
                single = single,
                pair = pair,
                fallback = fallback,
                sampleCount = observations.size,
            )
        }
        return result
    }

    private fun analyzeOne(photo: SourcePhoto): Observation? {
        val frame = loader.load(photo.uri)
        val preview = frame.preview
        try {
            var people = detector.detect(preview)
            if (people.isEmpty()) people = detector.detect(preview, SAMPLE_RECOVERY_CONFIDENCE)
            if (people.isEmpty()) return null

            val image = ImageSize(preview.width, preview.height)
            val selected = WrestlingSubjectSelector.select(image, people)
            if (selected.isEmpty()) return null
            val subject = union(selected)
            if (subject.width < 8.0 || subject.height < 8.0) return null

            val left = (subject.left / subject.width).coerceIn(0.0, MAX_MARGIN_RATIO)
            val right = ((image.width - subject.right) / subject.width).coerceIn(0.0, MAX_MARGIN_RATIO)
            val top = (subject.top / subject.height).coerceIn(0.0, MAX_MARGIN_RATIO)
            val bottom = ((image.height - subject.bottom) / subject.height).coerceIn(0.0, MAX_MARGIN_RATIO)

            return Observation(
                subjectCount = min(2, selected.size),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                landscape = image.width >= image.height,
            )
        } finally {
            if (!preview.isRecycled) preview.recycle()
        }
    }

    private fun buildStyle(items: List<Observation>): FramingStyle? {
        if (items.isEmpty()) return null
        return FramingStyle(
            leftSubjectWidths = median(items.map { it.left }),
            topSubjectHeights = median(items.map { it.top }),
            rightSubjectWidths = median(items.map { it.right }),
            bottomSubjectHeights = median(items.map { it.bottom }),
            landscapeShare = items.count { it.landscape }.toDouble() / items.size.toDouble(),
            sampleCount = items.size,
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun union(rects: List<RectD>): RectD = RectD(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom },
    )

    private data class Observation(
        val subjectCount: Int,
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val landscape: Boolean,
    )

    companion object {
        private const val MAX_SAMPLES_PER_SCENE = 12
        private const val SAMPLE_RECOVERY_CONFIDENCE = 0.10f
        private const val MAX_MARGIN_RATIO = 2.0
    }
}
