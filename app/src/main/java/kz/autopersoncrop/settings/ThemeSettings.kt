package kz.autopersoncrop.settings

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import kz.autopersoncrop.R

enum class ThemeMode(val label: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная"),
}

class ThemeSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)

    fun read(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
    }.getOrDefault(ThemeMode.SYSTEM)

    fun write(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
    }
}

fun Activity.applyStoredTheme() {
    val mode = ThemeSettingsStore(this).read()
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    setTheme(if (dark) R.style.Theme_AutoPersonCrop_Dark else R.style.Theme_AutoPersonCrop_Light)
}
