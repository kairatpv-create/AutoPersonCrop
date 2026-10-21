package kz.kairat.organizer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

private val cases = JSONArray()
private val sections = listOf("notes", "tasks", "projects", "projectStages", "projectJournal", "projectNoteLinks", "money", "reminders")
private fun test(name: String, body: () -> Unit) {
    try { body(); cases.put(JSONObject().put("name", name).put("result", "PASS")) }
    catch (e: Throwable) {
        cases.put(JSONObject().put("name", name).put("result", "FAIL").put("error", e.toString()))
        e.printStackTrace()
    }
}

private class Fixture(label: String) {
    val context = Context(Files.createTempDirectory("organizer-d04-synthetic-").toFile())
    val db = NoteDatabase(context)
    val settings = SettingsStore(context)
    init {
        AttachmentStore.initialize(context)
        settings.darkTheme = true; settings.fontScale = 1.3f; settings.buttonFontScale = 1.15f
        settings.boldText = true; settings.language = "ru"; settings.projectsEnabled = true
        settings.backupMode = "manual"; settings.backupEmail = "synthetic@example.invalid"
        val note = db.save(null, "$label: Заметка / Note", "Keep the whole body", "green")
        db.addTask("$label task", "Synthetic details")
        val project = db.saveProject(null, "$label project", "Description", "active", 0,
            100L, 200L, 987.65, "Client", "Address", "Contact", "red")
        db.addProjectStage(project, "$label stage", 300L)
        db.addProjectJournal(project, "$label journal"); db.linkNoteToProject(project, note)
        db.addMoneyEntry("expense", 12.5, "Synthetic category", project, "$label money", "yellow")
        db.addMoneyEntry("income", 99.99, "No project", null, "$label unlinked")
        db.addReminder("note", note, 400L, true, false, "system", "weekly", 5, "single", 600)
        val attachment = File(context.cacheDir, "synthetic.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 127, -1)) }
        AttachmentStore.addLocalFile("note", note, attachment)
    }
    fun activate() { AttachmentStore.initialize(context) }
    fun backup(): JSONObject { activate(); return JSONObject(BackupManager.createBackup(db, settings)) }
}

private fun files(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }.associate {
    it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
}
private data class Snapshot(val payload: JSONObject, val files: Map<String, String>)
private fun snapshot(f: Fixture): Snapshot {
    val payload = f.backup().apply { remove("createdAt") }
    // Includes the actual SQLite file, attachment bytes and index, not just exported record counts.
    return Snapshot(payload, files(f.context.sandbox).filterKeys { !it.startsWith("cache/") })
}
private fun reject(f: Fixture, name: String, payload: () -> String, lang: String = "en") = test(name) {
    f.activate()
    val before = snapshot(f)
    val result = runCatching { BackupManager.restore(payload(), f.db, lang) }
    check(result.isFailure) { "Malformed backup was accepted" }
    val message = result.exceptionOrNull()?.message.orEmpty()
    check(if (lang == "ru") message.contains("Данные не изменены") else message.contains("data has not been changed")) { message }
    val after = snapshot(f)
    check(before.payload.similar(after.payload)) { "Existing records or settings changed" }
    check(before.files == after.files) { "SQLite, attachment bytes or index changed" }
}
private fun clone(json: JSONObject) = JSONObject(json.toString())
private fun checkRestored(source: JSONObject, target: Fixture) {
    val actual = target.backup()
    sections.forEach { check(source.getJSONArray(it).similar(actual.getJSONArray(it))) { "Restore mismatch: $it" } }
    check(source.getJSONArray("attachments").similar(actual.getJSONArray("attachments")))
}

fun main() {
    val protected = Fixture("existing")
    val complete = protected.backup()
    reject(protected, "D04 notes-only JSON (RU)", { "{\"notes\":[]}" }, "ru")
    reject(protected, "Empty JSON object", { "{}" })
    for ((name, payload) in listOf(
        "Truncated JSON" to complete.toString().dropLast(5),
        "Text instead of JSON" to "this is not a backup",
        "Array instead of root object" to "[]",
        "Null root" to "null",
        "Trailing garbage" to (complete.toString() + "garbage")
    )) reject(protected, name, { payload })

    for (key in listOf("format", "version", "createdAt") + sections + listOf("settings", "attachments")) {
        reject(protected, "Missing required root section: $key", { clone(complete).apply { remove(key) }.toString() })
    }
    for (version in listOf(0L, 8L, 4294967302L)) {
        reject(protected, "Unsupported/overflowing version: $version", { clone(complete).put("version", version).toString() })
    }
    reject(protected, "Wrong format with otherwise complete structure", { clone(complete).put("format", "other-app").toString() })
    for (key in sections + listOf("attachments")) {
        for (value in listOf(JSONObject.NULL, JSONObject(), "[]", false)) {
            reject(protected, "Wrong section type: $key / ${value.javaClass.simpleName}", { clone(complete).put(key, value).toString() })
        }
        reject(protected, "Non-object entry: $key", { clone(complete).put(key, JSONArray().put(1)).toString() })
        reject(protected, "Missing record fields: $key", { clone(complete).put(key, JSONArray().put(JSONObject())).toString() })
    }
    for (value in listOf(JSONObject.NULL, JSONArray(), "settings", false)) {
        reject(protected, "Wrong settings type: ${value.javaClass.simpleName}", { clone(complete).put("settings", value).toString() })
    }
    // Every field emitted by the current exporter is checked, including optional fields when present.
    for (section in sections + listOf("attachments", "settings")) {
        val record = if (section == "settings") complete.getJSONObject(section) else complete.getJSONArray(section).getJSONObject(0)
        for (key in record.keys().asSequence().toList()) {
            reject(protected, "Wrong field type: $section.$key", {
                val changed = clone(complete)
                val objectToChange = if (section == "settings") changed.getJSONObject(section) else changed.getJSONArray(section).getJSONObject(0)
                val wrong = when (objectToChange.get(key)) { is Number -> "123"; is Boolean -> 1; else -> JSONObject() }
                objectToChange.put(key, wrong)
                changed.toString()
            })
        }
    }
    reject(protected, "Fractional record ID", { clone(complete).apply { getJSONArray("notes").getJSONObject(0).put("id", 1.5) }.toString() })
    reject(protected, "Out-of-range record ID", { complete.toString().replaceFirst("\"id\":1", "\"id\":9223372036854775808") })
    reject(protected, "Invalid attachment encoding before any database/file write", {
        clone(complete).apply { getJSONArray("attachments").getJSONObject(0).put("data", "!invalid-base64!") }.toString()
    })
    reject(protected, "Incomplete attachment bytes", {
        clone(complete).apply { getJSONArray("attachments").getJSONObject(0).put("data", "AA==") }.toString()
    })
    reject(protected, "Font scale exceeds Float range", {
        clone(complete).apply { getJSONObject("settings").put("buttonFontScale", 1e100) }.toString()
    })
    val requiredFields = mapOf(
        "notes" to "id title body createdAt updatedAt", "tasks" to "id title details done createdAt updatedAt",
        "projects" to "id name description createdAt", "projectStages" to "id projectId title done dueAt createdAt",
        "projectJournal" to "id projectId text createdAt", "projectNoteLinks" to "projectId noteId",
        "money" to "id type amount category projectName note createdAt", "reminders" to "id targetType targetId triggerAt createdAt",
        "attachments" to "id targetType targetId displayName storedName mimeType size createdAt data",
        "settings" to "darkTheme fontScale boldText backupMode email language"
    )
    for ((section, keys) in requiredFields) for (key in keys.split(' ')) {
        reject(protected, "Missing required field: $section.$key", {
            clone(complete).apply {
                (if (section == "settings") getJSONObject(section) else getJSONArray(section).getJSONObject(0)).remove(key)
            }.toString()
        })
    }
    reject(protected, "Incomplete JSON inside ZIP", {
        val uri = Uri(File(protected.context.cacheDir, "incomplete.zip").path)
        BackupManager.writeArchiveToUri(protected.context, uri, "{\"notes\":[]}")
        BackupManager.readFromUri(protected.context, uri)
    })

    val source = Fixture("restored")
    val payload = source.backup()
    for (archived in listOf(false, true)) test("Complete ${if (archived) "ZIP" else "JSON"} restores all sections, preferences and binary attachment") {
        val uri = Uri(File(source.context.cacheDir, if (archived) "complete.zip" else "complete.json").path)
        if (archived) BackupManager.writeArchiveToUri(source.context, uri, payload.toString())
        else BackupManager.writeToUri(source.context, uri, payload.toString())
        val read = BackupManager.readFromUri(source.context, uri)
        val target = Fixture("old target")
        val restored = BackupManager.restore(read, target.db, "ru")
        checkRestored(payload, target)
        check(restored.darkTheme == true && restored.boldText == true && restored.language == "ru")
        check(restored.fontScale == 1.3f && restored.buttonFontScale == 1.15f && restored.projectsEnabled == true)
        check(restored.email == "synthetic@example.invalid" && restored.backupMode == "manual")
    }
    test("A complete empty backup is accepted and intentionally restores empty collections") {
        val target = Fixture("to clear")
        val empty = clone(payload)
        (sections + "attachments").forEach { empty.put(it, JSONArray()) }
        BackupManager.restore(empty.toString(), target.db)
        checkRestored(empty, target)
    }
    for (version in 3..5) test("Legacy file backup v$version retains optional-field defaults") {
        val target = Fixture("legacy target")
        val legacy = clone(payload).put("version", version)
        for (section in listOf("notes", "projects", "money")) {
            val a = legacy.getJSONArray(section)
            for (i in 0 until a.length()) a.getJSONObject(i).remove("colorKey")
        }
        legacy.getJSONObject("settings").apply { remove("contentFontScale"); remove("buttonFontScale"); remove("projectsEnabled") }
        val restored = BackupManager.restore(legacy.toString(), target.db)
        check(target.db.getNotes().single().colorKey == "blue")
        check(target.db.getMoneyEntries().size == 2 && target.db.getMoneyEntries().any { it.amount == 12.5 })
        check(restored.fontScale == 1.3f && restored.buttonFontScale == 1.3f && restored.projectsEnabled == null)
    }
    test("Cloud v7 accepts all data sections without settings/attachments and preserves local files") {
        val target = Fixture("cloud target")
        val cloud = clone(payload).apply { remove("settings"); remove("attachments"); put("version", 7); put("createdAt", 0); put("cloudSchema", "organizer-sync-v3") }
        cloud.getJSONArray("money").getJSONObject(0).put("updatedAt", 123L) // Web exporter extension.
        val attachmentFiles = files(File(target.context.filesDir, "attachments"))
        BackupManager.restore(cloud.toString(), target.db)
        val actual = target.backup()
        sections.forEach { check(payload.getJSONArray(it).similar(actual.getJSONArray(it))) }
        check(attachmentFiles == files(File(target.context.filesDir, "attachments")))
    }
    val cloudTarget = Fixture("protected cloud target")
    val cloud = clone(payload).apply { remove("settings"); remove("attachments"); put("version", 7); put("createdAt", 0); put("cloudSchema", "organizer-sync-v3") }
    for (key in sections) reject(cloudTarget, "Incomplete cloud v7: missing $key", { clone(cloud).apply { remove(key) }.toString() })
    test("Complete empty cloud v7 is accepted and preserves existing attachment files") {
        val target = Fixture("empty cloud target")
        val empty = clone(cloud)
        sections.forEach { empty.put(it, JSONArray()) }
        val attachmentFiles = files(File(target.context.filesDir, "attachments"))
        BackupManager.restore(empty.toString(), target.db)
        val actual = target.backup()
        sections.forEach { check(actual.getJSONArray(it).length() == 0) }
        check(attachmentFiles == files(File(target.context.filesDir, "attachments")))
    }

    println(JSONObject().put("suite", "D04 backup validation (synthetic host SQLite)").put("cases", cases)
        .put("passed", (0 until cases.length()).count { cases.getJSONObject(it).getString("result") == "PASS" })
        .put("total", cases.length()).toString(2))
    check((0 until cases.length()).all { cases.getJSONObject(it).getString("result") == "PASS" }) { "D04 regression failures" }
}
