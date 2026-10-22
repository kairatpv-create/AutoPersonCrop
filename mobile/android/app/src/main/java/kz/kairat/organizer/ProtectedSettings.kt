package kz.kairat.organizer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Device credentials never enter file sharing, manual exports or Android system backup. */
internal class ProtectedSettings(
    private val context: Context,
    private val cipher: ProtectedSettingsCipher = ProtectedSettingsCipher(context),
    private val io: AttachmentRestoreFiles = AttachmentRestoreFiles()
) {
    companion object {
        private val lock = Any()
        val keys = setOf("sync_id_token", "sync_refresh_token", "sync_uid", "sync_token_expiry",
            "sync_api_key", "pin_hash", "pin_salt", "pin_enabled", "pin_attempts", "pin_locked_until")
    }
    class Unavailable(cause: Throwable) : IOException("Protected settings are unavailable; existing data has been kept", cause)
    private val directory get() = File(context.noBackupFilesDir, "protected-settings")
    internal val file get() = File(directory, "credentials.bin")

    fun ensure() { read { Unit } }
    fun string(key: String) = read { it.optString(key, "") }
    fun long(key: String) = read { it.optLong(key, 0) }
    fun boolean(key: String) = read { it.optBoolean(key, false) }
    fun put(key: String, value: Any) = update { it.put(key, value); Unit }
    fun remove(vararg names: String) = update { values -> names.forEach(values::remove) }
    fun update(change: (JSONObject) -> Unit) = guarded {
        val values = loadAndMigrate()
        val before = values.toString()
        change(values)
        if(values.toString()!=before) write(values)
    }
    private fun <T> read(action: (JSONObject) -> T): T = guarded { action(loadAndMigrate()) }
    private fun <T> guarded(action: () -> T): T = synchronized(lock) {
        try { action() } catch (failure: Exception) {
            if (failure is Unavailable) throw failure
            throw Unavailable(failure)
        }
    }
    private fun loadAndMigrate(): JSONObject {
        val legacy = context.getSharedPreferences("organizer_settings", Context.MODE_PRIVATE)
        val values = if (file.exists()) decode(file.readBytes()) else {
            val old = JSONObject()
            for ((key, value) in legacy.all) if (key in keys && value != null) old.put(key, value)
            // Persist and read back authenticated bytes BEFORE removing any legacy credential.
            write(old)
            old
        }
        val editor = legacy.edit()
        keys.forEach(editor::remove)
        check(editor.commit()) { "Cannot finish protected settings migration" }
        return values
    }
    private fun decode(bytes: ByteArray): JSONObject {
        require(bytes.size in 1..1024 * 1024)
        val root = JSONObject(cipher.decrypt(bytes).toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val values = root.getJSONObject("values")
        for (key in values.keys()) {
            require(key in keys)
            val value = values.get(key)
            require(when (key) {
                "pin_enabled" -> value is Boolean
                "sync_token_expiry", "pin_attempts", "pin_locked_until" -> value is Number
                else -> value is String
            })
        }
        return values
    }
    private fun write(values: JSONObject) {
        val plain = JSONObject().put("version", 1).put("values", values).toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plain)
        decode(encrypted)
        require(cipher.decrypt(encrypted).contentEquals(plain))
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "credentials.pending")
        io.write(temporary, encrypted)
        require(temporary.readBytes().contentEquals(encrypted))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        io.syncDirectory(directory)
        io.syncDirectory(context.noBackupFilesDir)
    }
}

/** UI preferences remain eligible for device backup; the obsolete mixed file is excluded. */
internal object SettingsPreferences {
    fun open(context: Context): android.content.SharedPreferences {
        val target = context.getSharedPreferences("organizer_preferences", Context.MODE_PRIVATE)
        synchronized(this) {
            if (!target.getBoolean("protected_preferences_migrated", false)) {
                val old = context.getSharedPreferences("organizer_settings", Context.MODE_PRIVATE)
                val editor = target.edit()
                for ((key, value) in old.all) if (key !in ProtectedSettings.keys) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                }
                check(editor.putBoolean("protected_preferences_migrated", true).commit()) { "Cannot migrate interface preferences" }
            }
        }
        return target
    }
}
