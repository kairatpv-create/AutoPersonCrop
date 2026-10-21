package kz.kairat.organizer

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal
import java.util.Base64

/** Read-only preflight. Never infer an empty collection from a missing backup section. */
internal object BackupValidation {
    private class Invalid(val field: String) : RuntimeException()

    fun parse(json: String, lang: String): JSONObject {
        try {
            val input = JSONTokener(json)
            val root = input.nextValue() as? JSONObject ?: throw Invalid("JSON")
            if (input.nextClean() != '\u0000') throw Invalid("JSON")
            if (root.opt("format") != "my-organizer-backup") throw Invalid("format")
            fields(root, "", longs = "createdAt", ints = "version")
            // The recovered file exporters use versions 3–6; cloud snapshots use version 7.
            val version = root.getInt("version")
            if (version !in 3..7) throw Invalid("version")

            records(root, "notes") { o, path ->
                fields(o, path, strings = "title body colorKey", longs = "id createdAt updatedAt", optional = "colorKey")
            }
            records(root, "tasks") { o, path ->
                fields(o, path, strings = "title details", longs = "id createdAt updatedAt", booleans = "done")
            }
            records(root, "projects") { o, path ->
                fields(o, path, strings = "name description status client address contact colorKey",
                    longs = "id createdAt updatedAt startAt dueAt", ints = "progress", numbers = "budget",
                    optional = "status client address contact colorKey updatedAt startAt dueAt progress budget")
            }
            records(root, "projectStages") { o, path ->
                fields(o, path, strings = "title", longs = "id projectId dueAt createdAt", booleans = "done")
            }
            records(root, "projectJournal") { o, path ->
                fields(o, path, strings = "text", longs = "id projectId createdAt")
            }
            records(root, "projectNoteLinks") { o, path -> fields(o, path, longs = "projectId noteId") }
            records(root, "money") { o, path ->
                fields(o, path, strings = "type category projectName note colorKey", longs = "id projectId createdAt",
                    numbers = "amount", optional = "projectId colorKey")
            }
            records(root, "reminders") { o, path ->
                fields(o, path, strings = "targetType soundKey scheduleType dayMode", longs = "id targetId triggerAt createdAt",
                    booleans = "sound vibrate", ints = "daysMask baseMinutes",
                    optional = "soundKey scheduleType dayMode sound vibrate daysMask baseMinutes")
            }

            // A cloud snapshot deliberately omits local preferences and attachment bytes.
            // Local files must contain both sections, including when they are empty.
            if (version < 7 || root.has("settings")) {
                val settings = root.opt("settings") as? JSONObject ?: throw Invalid("settings")
                fields(settings, "settings", strings = "backupMode email language",
                    booleans = "darkTheme boldText projectsEnabled",
                    numbers = "fontScale contentFontScale buttonFontScale",
                    optional = "projectsEnabled contentFontScale buttonFontScale")
                for (key in listOf("fontScale", "contentFontScale", "buttonFontScale")) {
                    if (settings.has(key) && !settings.getDouble(key).toFloat().isFinite()) throw Invalid("settings.$key")
                }
            }
            if (version < 7 || root.has("attachments")) {
                records(root, "attachments") { o, path ->
                    fields(o, path, strings = "targetType displayName storedName mimeType data",
                        longs = "id targetId size createdAt durationMs", optional = "durationMs")
                    // Decode now, before restoreBackup can remove any existing files.
                    val bytes = try { Base64.getDecoder().decode(o.getString("data")) }
                    catch (_: IllegalArgumentException) { throw Invalid("$path.data") }
                    if (bytes.size.toLong() != o.getLong("size")) throw Invalid("$path.size")
                }
            }
            return root
        } catch (e: Invalid) {
            throw error(lang, e.field)
        } catch (_: JSONException) {
            throw error(lang, "JSON")
        }
    }

    private fun records(root: JSONObject, key: String, validate: (JSONObject, String) -> Unit) {
        val array = root.opt(key) as? JSONArray ?: throw Invalid(key)
        for (i in 0 until array.length()) {
            val path = "$key[$i]"
            val record = array.opt(i) as? JSONObject ?: throw Invalid(path)
            validate(record, path)
        }
    }

    private fun fields(o: JSONObject, path: String, strings: String = "", longs: String = "",
                       ints: String = "", numbers: String = "", booleans: String = "", optional: String = "") {
        val optionalKeys = optional.split(' ').toSet()
        fun checkKeys(keys: String, accepts: (Any?) -> Boolean) {
            for (key in keys.split(' ').filter { it.isNotEmpty() }) {
                if (!o.has(key) && key in optionalKeys) continue
                if (!accepts(o.opt(key))) throw Invalid(if (path.isEmpty()) key else "$path.$key")
            }
        }
        checkKeys(strings) { it is String }
        checkKeys(booleans) { it is Boolean }
        checkKeys(numbers) { it is Number && it.toDouble().isFinite() }
        checkKeys(longs) { integer(it, Long.MIN_VALUE, Long.MAX_VALUE) }
        checkKeys(ints) { integer(it, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) }
    }

    private fun integer(value: Any?, min: Long, max: Long): Boolean {
        if (value !is Number) return false
        return try { BigDecimal(value.toString()).longValueExact() in min..max }
        catch (_: ArithmeticException) { false }
        catch (_: NumberFormatException) { false }
    }

    private fun error(lang: String, field: String): IllegalArgumentException {
        val message = if (AppStrings.normalizeLanguage(lang) == "ru")
            "Резервная копия повреждена, неполна или имеет неподдерживаемый формат. Данные не изменены."
        else "The backup is damaged, incomplete or uses an unsupported format. Your data has not been changed."
        return IllegalArgumentException("$message ($field)")
    }
}
