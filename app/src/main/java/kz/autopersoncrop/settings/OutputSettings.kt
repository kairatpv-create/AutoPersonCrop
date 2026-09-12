package kz.autopersoncrop.settings

import android.content.Context

enum class OutputQuality(val label: String, val jpegQuality: Int) {
    LOW("Низкое", 78),
    MEDIUM("Среднее", 88),
    HIGH("Высокое", 96),
}

enum class OutputResolution(val label: String, val maxLongSide: Int) {
    ORIGINAL("Оригинальное", 0),
    UHD_4K("4K (3840 px)", 3840),
    QHD_2K("2K (2560 px)", 2560),
    FULL_HD("Full HD (1920 px)", 1920),
}

data class OutputSettings(
    val quality: OutputQuality = OutputQuality.HIGH,
    val resolution: OutputResolution = OutputResolution.ORIGINAL,
) {
    val strictLossless: Boolean
        get() = quality == OutputQuality.HIGH && resolution == OutputResolution.ORIGINAL
}

class OutputSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("output_settings", Context.MODE_PRIVATE)

    fun read(): OutputSettings {
        val quality = runCatching {
            OutputQuality.valueOf(prefs.getString(KEY_QUALITY, OutputQuality.HIGH.name)!!)
        }.getOrDefault(OutputQuality.HIGH)
        val resolution = runCatching {
            OutputResolution.valueOf(prefs.getString(KEY_RESOLUTION, OutputResolution.ORIGINAL.name)!!)
        }.getOrDefault(OutputResolution.ORIGINAL)
        return OutputSettings(quality, resolution)
    }

    fun write(settings: OutputSettings) {
        prefs.edit()
            .putString(KEY_QUALITY, settings.quality.name)
            .putString(KEY_RESOLUTION, settings.resolution.name)
            .apply()
    }

    companion object {
        private const val KEY_QUALITY = "quality"
        private const val KEY_RESOLUTION = "resolution"
    }
}
