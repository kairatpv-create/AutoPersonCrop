package kz.autopersoncrop.settings

import android.content.Context

enum class OutputQuality(
    val label: String,
    val jpegQuality: Int,
    val lossless: Boolean,
) {
    ORIGINAL_LOSSLESS("Оригинал • без пересжатия", 100, true),
    HIGH_95("Высокое • JPEG 95", 95, false),
    MEDIUM_88("Среднее • JPEG 88", 88, false),
    COMPACT_80("Экономное • JPEG 80", 80, false),
}

enum class OutputResolution(val label: String, val maxLongSide: Int) {
    ORIGINAL("Оригинальное", 0),
}

data class OutputSettings(
    val quality: OutputQuality = OutputQuality.ORIGINAL_LOSSLESS,
    val resolution: OutputResolution = OutputResolution.ORIGINAL,
) {
    val strictLossless: Boolean get() = quality.lossless
}

class OutputSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("output_settings", Context.MODE_PRIVATE)

    fun read(): OutputSettings {
        val qualityName = prefs.getString(KEY_QUALITY, null)
        val quality = when (qualityName) {
            // Names used by 0.5.0. That release always forced HIGH as its lossless default.
            "LOW" -> OutputQuality.COMPACT_80
            "MEDIUM" -> OutputQuality.MEDIUM_88
            "HIGH" -> OutputQuality.ORIGINAL_LOSSLESS
            else -> runCatching {
                OutputQuality.valueOf(qualityName ?: OutputQuality.ORIGINAL_LOSSLESS.name)
            }.getOrDefault(OutputQuality.ORIGINAL_LOSSLESS)
        }
        return OutputSettings(quality = quality, resolution = OutputResolution.ORIGINAL)
    }

    fun write(settings: OutputSettings) {
        prefs.edit()
            .putString(KEY_QUALITY, settings.quality.name)
            .putString(KEY_RESOLUTION, OutputResolution.ORIGINAL.name)
            .apply()
    }

    companion object {
        private const val KEY_QUALITY = "quality"
        private const val KEY_RESOLUTION = "resolution"
    }
}
