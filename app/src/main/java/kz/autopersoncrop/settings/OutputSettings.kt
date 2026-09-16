package kz.autopersoncrop.settings

import android.content.Context

enum class OutputQuality(val label: String, val jpegQuality: Int) {
    LOW("Низкое", 78),
    MEDIUM("Среднее", 88),
    HIGH("Оригинальное", 100),
}

enum class OutputResolution(val label: String, val maxLongSide: Int) {
    ORIGINAL("Оригинальное", 0),
    UHD_4K("4K (3840 px)", 3840),
    QHD_2K("2K (2560 px)", 2560),
    FULL_HD("Full HD (1920 px)", 1920),
}

/**
 * AutoPersonCrop 0.5+ edits only the crop rectangle. Output is always kept at the original
 * resolution and uses the native lossless JPEG transformer, so there is no JPEG recompression.
 */
data class OutputSettings(
    val quality: OutputQuality = OutputQuality.HIGH,
    val resolution: OutputResolution = OutputResolution.ORIGINAL,
) {
    val strictLossless: Boolean get() = true
}

class OutputSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("output_settings", Context.MODE_PRIVATE)

    fun read(): OutputSettings {
        // Migrate any old reduced-quality/reduced-resolution preference to the new lossless rule.
        prefs.edit()
            .putString(KEY_QUALITY, OutputQuality.HIGH.name)
            .putString(KEY_RESOLUTION, OutputResolution.ORIGINAL.name)
            .apply()
        return OutputSettings(OutputQuality.HIGH, OutputResolution.ORIGINAL)
    }

    fun write(settings: OutputSettings) {
        prefs.edit()
            .putString(KEY_QUALITY, OutputQuality.HIGH.name)
            .putString(KEY_RESOLUTION, OutputResolution.ORIGINAL.name)
            .apply()
    }

    companion object {
        private const val KEY_QUALITY = "quality"
        private const val KEY_RESOLUTION = "resolution"
    }
}
