package kz.autopersoncrop.settings

import android.content.Context

enum class OutputQuality(
    val label: String,
    val jpegQuality: Int,
    val lossless: Boolean,
) {
    ORIGINAL("Оригинал • без пересжатия", 100, true),
    HIGH("Высокое • JPEG 95", 95, false),
    MEDIUM("Среднее • JPEG 88", 88, false),
    COMPACT("Экономное • JPEG 80", 80, false),
}

enum class OutputResolution(val label: String, val maxLongSide: Int) {
    ORIGINAL("Оригинальное", 0),
}

data class OutputSettings(
    val quality: OutputQuality = OutputQuality.ORIGINAL,
    val resolution: OutputResolution = OutputResolution.ORIGINAL,
) {
    val strictLossless: Boolean get() = quality.lossless
}

class OutputSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("output_settings", Context.MODE_PRIVATE)

    fun read(): OutputSettings {
        val qualityName = prefs.getString(KEY_QUALITY, null)
        val quality = when (qualityName) {
            // Migrate the previous 0.5.0 names without changing the user's original-quality default.
            "LOW" -> OutputQuality.COMPACT
            "MEDIUM" -> OutputQuality.MEDIUM
            "HIGH" -> OutputQuality.ORIGINAL
            else -> runCatching { OutputQuality.valueOf(qualityName ?: OutputQuality.ORIGINAL.name) }
                .getOrDefault(OutputQuality.ORIGINAL)
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
