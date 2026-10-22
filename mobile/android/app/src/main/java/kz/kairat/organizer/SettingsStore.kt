package kz.kairat.organizer

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

class SettingsStore(context: Context) {
    internal val appContext = context.applicationContext
    var syncError: String
        get() = prefs.getString("sync_error", "") ?: ""
        set(v) = prefs.edit().putString("sync_error", v).apply()
    internal fun saveSession(email:String,token:String,uid:String) {
        secure.update { it.put("sync_id_token", token).put("sync_uid", uid).put("sync_refresh_token", "").put("sync_token_expiry", Long.MAX_VALUE) }
        check(prefs.edit().putString("sync_email",email).commit()) { "Could not save session" }
    }
    private val prefs: SharedPreferences = SettingsPreferences.open(appContext)
    private val secure = ProtectedSettings(appContext)
    internal fun ensureSecureStorage() = secure.ensure()

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
        get() = secure.string("sync_api_key")
        set(v) = secure.put("sync_api_key", v.trim())
    var syncEmail: String
        get() = prefs.getString("sync_email", "") ?: ""
        set(v) = prefs.edit().putString("sync_email", v.trim()).apply()
    var syncIdToken: String
        get() = secure.string("sync_id_token")
        set(v) = secure.put("sync_id_token", v)
    var syncRefreshToken: String
        get() = secure.string("sync_refresh_token")
        set(v) = secure.put("sync_refresh_token", v)
    var syncUid: String
        get() = secure.string("sync_uid")
        set(v) = secure.put("sync_uid", v)
    var syncTokenExpiry: Long
        get() = secure.long("sync_token_expiry")
        set(v) = secure.put("sync_token_expiry", v)
    var syncLastFingerprint: String
        get() = prefs.getString("sync_last_fingerprint", "") ?: ""
        set(v) = prefs.edit().putString("sync_last_fingerprint", v).apply()
    var syncLastCloudUpdatedAt: Long
        get() = prefs.getLong("sync_last_cloud_updated_at", 0L)
        set(v) = prefs.edit().putLong("sync_last_cloud_updated_at", v).apply()
    var syncLastSuccessAt: Long
        get() = prefs.getLong("sync_last_success_at", 0L)
        set(v) = prefs.edit().putLong("sync_last_success_at", v).apply()
    fun clearSyncSession() {
        secure.remove("sync_id_token", "sync_refresh_token", "sync_uid", "sync_token_expiry")
        check(prefs.edit().remove("sync_last_fingerprint").remove("sync_last_cloud_updated_at").remove("sync_last_success_at").commit())
    }

    // A storage failure must never disable an existing PIN or reveal reminder content.
    fun isPinProtectionEnabled(): Boolean = try { secure.boolean("pin_enabled") && hasPin() } catch (_: ProtectedSettings.Unavailable) { true }
    fun setPinProtectionEnabled(enabled: Boolean) = secure.put("pin_enabled", enabled)
    fun hasPin(): Boolean = secure.string("pin_hash").isNotBlank() && secure.string("pin_salt").isNotBlank()

    fun setPin(pin: String) {
        require(pin.length >= 4)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        secure.update { it.put("pin_salt", salt).put("pin_hash", hashPin(pin, salt)).put("pin_attempts",0).put("pin_locked_until",0L) }
    }
    fun pinRetrySeconds(): Long = try { ((secure.long("pin_locked_until")-System.currentTimeMillis()+999)/1000).coerceAtLeast(0) } catch (_: ProtectedSettings.Unavailable) { 30 }
    fun verifyPin(pin: String): Boolean = try {
        var accepted=false
        secure.update { values ->
            val now=System.currentTimeMillis()
            if(values.optLong("pin_locked_until",0)>now) return@update
            val salt=values.optString("pin_salt","");val expected=values.optString("pin_hash","")
            accepted=salt.isNotEmpty()&&expected.isNotEmpty()&&constantTimeEquals(expected,hashPin(pin,salt))
            val attempts=if(accepted||values.optLong("pin_locked_until",0)>0) 0 else values.optInt("pin_attempts",0)
            if(accepted) values.put("pin_attempts",0).put("pin_locked_until",0L)
            else if(attempts+1>=5) values.put("pin_attempts",0).put("pin_locked_until",now+30_000)
            else values.put("pin_attempts",attempts+1).put("pin_locked_until",0L)
        }
        accepted
    } catch (_: ProtectedSettings.Unavailable) { false }
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
