package kz.kairat.organizer

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("organizer_settings", Context.MODE_PRIVATE)

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", false)
        set(v) = prefs.edit().putBoolean("dark_theme", v).apply()
    var fontScale: Float
        get() = prefs.getFloat("content_font_scale", prefs.getFloat("font_scale", 1f))
        set(v) {
            val value=v.coerceIn(.85f,1.45f)
            prefs.edit().putFloat("content_font_scale",value).putFloat("font_scale",value).apply()
        }
    var buttonFontScale: Float
        get() = prefs.getFloat("button_font_scale", prefs.getFloat("font_scale", 1f))
        set(v) = prefs.edit().putFloat("button_font_scale", v.coerceIn(.85f, 1.45f)).apply()
    var boldText: Boolean
        get() = prefs.getBoolean("bold_text", false)
        set(v) = prefs.edit().putBoolean("bold_text", v).apply()
    var projectsEnabled: Boolean
        get() = prefs.getBoolean("projects_enabled", true)
        set(v) = prefs.edit().putBoolean("projects_enabled", v).apply()
    var backupMode: String
        get() = prefs.getString("backup_mode", "manual") ?: "manual"
        set(v) = prefs.edit().putString("backup_mode", v).apply()
    var backupEmail: String
        get() = prefs.getString("backup_email", "") ?: ""
        set(v) = prefs.edit().putString("backup_email", v).apply()
    var lastBackupAt: Long
        get() = prefs.getLong("last_backup_at", 0L)
        set(v) = prefs.edit().putLong("last_backup_at", v).apply()
    var language: String
        get() = prefs.getString("language", null)?.let(AppStrings::normalizeLanguage)
            ?: AppStrings.normalizeLanguage(java.util.Locale.getDefault().toLanguageTag())
        set(v) = prefs.edit().putString("language", AppStrings.normalizeLanguage(v)).apply()

    /** Only exported preferences; account credentials and PIN are deliberately untouched. */
    internal fun applyRestoredPreferences(p: RestoredPreferences): Boolean {
        val editor = prefs.edit().putString("backup_mode", p.backupMode).putString("backup_email", p.email)
        p.darkTheme?.let { editor.putBoolean("dark_theme", it) }
        p.fontScale?.coerceIn(.85f, 1.45f)?.let { editor.putFloat("content_font_scale", it).putFloat("font_scale", it) }
        p.buttonFontScale?.let { editor.putFloat("button_font_scale", it.coerceIn(.85f, 1.45f)) }
        p.boldText?.let { editor.putBoolean("bold_text", it) }
        p.projectsEnabled?.let { editor.putBoolean("projects_enabled", it) }
        p.language?.let { editor.putString("language", AppStrings.normalizeLanguage(it)) }
        return editor.commit()
    }

    var syncProjectId: String
        get() = prefs.getString("sync_project_id", "") ?: ""
        set(v) = prefs.edit().putString("sync_project_id", v.trim()).apply()
    var syncApiKey: String
        get() = prefs.getString("sync_api_key", "") ?: ""
        set(v) = prefs.edit().putString("sync_api_key", v.trim()).apply()
    var syncEmail: String
        get() = prefs.getString("sync_email", "") ?: ""
        set(v) = prefs.edit().putString("sync_email", v.trim()).apply()
    var syncIdToken: String
        get() = prefs.getString("sync_id_token", "") ?: ""
        set(v) = prefs.edit().putString("sync_id_token", v).apply()
    var syncRefreshToken: String
        get() = prefs.getString("sync_refresh_token", "") ?: ""
        set(v) = prefs.edit().putString("sync_refresh_token", v).apply()
    var syncUid: String
        get() = prefs.getString("sync_uid", "") ?: ""
        set(v) = prefs.edit().putString("sync_uid", v).apply()
    var syncTokenExpiry: Long
        get() = prefs.getLong("sync_token_expiry", 0L)
        set(v) = prefs.edit().putLong("sync_token_expiry", v).apply()
    var syncLastFingerprint: String
        get() = prefs.getString("sync_last_fingerprint", "") ?: ""
        set(v) = prefs.edit().putString("sync_last_fingerprint", v).apply()
    var syncLastCloudUpdatedAt: Long
        get() = prefs.getLong("sync_last_cloud_updated_at", 0L)
        set(v) = prefs.edit().putLong("sync_last_cloud_updated_at", v).apply()
    var syncLastSuccessAt: Long
        get() = prefs.getLong("sync_last_success_at", 0L)
        set(v) = prefs.edit().putLong("sync_last_success_at", v).apply()
    fun clearSyncSession() = prefs.edit()
        .remove("sync_id_token").remove("sync_refresh_token").remove("sync_uid").remove("sync_token_expiry")
        .remove("sync_last_fingerprint").remove("sync_last_cloud_updated_at").remove("sync_last_success_at").apply()

    fun isPinProtectionEnabled(): Boolean = prefs.getBoolean("pin_enabled", false) && hasPin()
    fun setPinProtectionEnabled(enabled: Boolean) = prefs.edit().putBoolean("pin_enabled", enabled).apply()
    fun hasPin(): Boolean = !prefs.getString("pin_hash", "").isNullOrBlank() && !prefs.getString("pin_salt", "").isNullOrBlank()

    fun setPin(pin: String) {
        require(pin.length >= 4)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs.edit().putString("pin_salt", salt).putString("pin_hash", hashPin(pin, salt)).apply()
    }
    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null) ?: return false
        val expected = prefs.getString("pin_hash", null) ?: return false
        return constantTimeEquals(expected, hashPin(pin, salt))
    }
    private fun hashPin(pin: String, salt: String): String = MessageDigest.getInstance("SHA-256")
        .digest((salt + ":" + pin).toByteArray(Charsets.UTF_8)).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
