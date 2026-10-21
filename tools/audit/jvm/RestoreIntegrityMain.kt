package kz.kairat.organizer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

private val results = JSONArray()
private val tables = linkedMapOf("notes" to "notes", "tasks" to "tasks", "projects" to "projects",
    "money" to "money_entries", "reminders" to "reminders", "projectStages" to "project_stages",
    "projectJournal" to "project_journal", "projectNoteLinks" to "project_note_links")
private fun test(name: String, body: () -> Unit) {
    try { body(); results.put(JSONObject().put("name", name).put("result", "PASS")) }
    catch (e: Throwable) {
        results.put(JSONObject().put("name", name).put("result", "FAIL").put("error", e.toString()))
        e.printStackTrace()
    }
}
private class Fixture(label: String) {
    val context = Context(Files.createTempDirectory("organizer-d05-synthetic-").toFile())
    val db = NoteDatabase(context)
    val settings = SettingsStore(context)
    init {
        AttachmentStore.initialize(context)
        settings.language = "ru"; settings.fontScale = 1.3f; settings.buttonFontScale = 1.15f; settings.boldText = true
        val notes = (1..2).map { db.save(null, "$label note $it", "Синтетический текст $it", "green") }
        (1..2).forEach { db.addTask("$label task $it", "Details $it") }
        val projects = (1..2).map { db.saveProject(null, "$label project $it", "Description $it", "active", 0,
            100L, 200L, 987.65 + it, "Client", "Address", "Contact", "red") }
        projects.forEachIndexed { i, p -> db.addProjectStage(p, "$label stage $i", 300L); db.addProjectJournal(p, "$label journal $i") }
        db.linkNoteToProject(projects[0], notes[0]); db.linkNoteToProject(projects[0], notes[1]); db.linkNoteToProject(projects[1], notes[0])
        db.addMoneyEntry("expense", 12.5, "Category", projects[0], "$label expense")
        db.addMoneyEntry("income", 99.99, "Other", null, "$label income")
        db.addReminder("note", notes[0], 400L, true, false)
        db.addReminder("project", projects[1], 500L, false, true)
        db.hideSuggestion("money_category", "Synthetic hidden suggestion")
        notes.forEachIndexed { i, n ->
            val f = File(context.cacheDir, "synthetic-$i.bin").apply { writeBytes(byteArrayOf(0, 1, -1) + "$label bytes $i".toByteArray()) }
            AttachmentStore.addLocalFile("note", n, f)
        }
    }
    fun activate() { AttachmentStore.initialize(context) }
    fun backup(): JSONObject { activate(); return JSONObject(BackupManager.createBackup(db, settings)) }
}
private fun files(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
    it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
}
private data class Snapshot(val data: JSONObject, val files: Map<String, String>)
private fun snapshot(f: Fixture) = Snapshot(f.backup().apply { remove("createdAt") },
    files(f.context.sandbox).filterKeys { !it.startsWith("cache/") })
private fun unchanged(before: Snapshot, target: Fixture) {
    val after = snapshot(target)
    check(before.data.similar(after.data)) { "Existing records, preferences or attachments changed" }
    check(before.files == after.files) { "SQLite bytes, counters, attachment bytes or index changed" }
}
private fun clone(o: JSONObject) = JSONObject(o.toString())
private fun assertRestored(expected: JSONObject, target: Fixture) {
    val actual = target.backup()
    (tables.keys + "attachments").forEach { key ->
        check(expected.getJSONArray(key).similar(actual.getJSONArray(key))) { "Incomplete restore: $key" }
    }
}
private fun incoming(source: Fixture): JSONObject {
    val payload = source.backup()
    // Advance every restored identity to test that rollback restores AUTOINCREMENT counters too.
    for (section in tables.keys + "attachments") {
        val rows = payload.getJSONArray(section)
        for (i in 0 until rows.length()) for (key in listOf("id", "projectId", "noteId", "targetId")) {
            val record = rows.getJSONObject(i)
            if (record.has(key)) record.put(key, record.getLong(key) + 1000L)
        }
    }
    return payload
}
private fun duplicate(payload: JSONObject, section: String, conflicting: Boolean): JSONObject = clone(payload).apply {
    val rows = getJSONArray(section)
    val copy = clone(rows.getJSONObject(0))
    if (conflicting) when (section) {
        "notes", "tasks", "projectStages" -> copy.put("title", "Conflicting title")
        "projects" -> copy.put("name", "Conflicting project")
        "money" -> copy.put("amount", 123456.78)
        "reminders" -> copy.put("triggerAt", 999999L)
        "projectJournal" -> copy.put("text", "Conflicting journal")
        "attachments" -> copy.put("displayName", "Conflicting attachment")
    }
    rows.put(copy) // Non-adjacent duplicate; never silently keep just the first/last record.
}
private fun restoreDirect(source: NoteDatabase, target: NoteDatabase, section: String) {
    val n = source.getNotes(); val t = source.getTasks(); val p = source.getProjects(); val m = source.getMoneyEntries()
    val r = source.getReminders(); val s = source.getAllProjectStages(); val j = source.getAllProjectJournal(); val l = source.getProjectNoteLinks()
    target.replaceAll(if (section == "notes") n + n.first().copy(title = "Conflicting note") else n,
        if (section == "tasks") t + t.first() else t, if (section == "projects") p + p.first() else p,
        if (section == "money") m + m.first() else m, if (section == "reminders") r + r.first() else r,
        if (section == "projectStages") s + s.first() else s, if (section == "projectJournal") j + j.first() else j,
        if (section == "projectNoteLinks") l + l.first() else l)
}

fun main() {
    val source = Fixture("incoming")
    val payload = incoming(source)
    val protected = Fixture("existing")
    for (version in listOf(6, 7)) for (section in tables.keys + if (version == 6) listOf("attachments") else emptyList()) {
        for (conflicting in if (section == "projectNoteLinks") listOf(false) else listOf(false, true)) {
            test("v$version rejects ${if (conflicting) "conflicting" else "identical"} duplicate in $section before writes") {
                val input = duplicate(payload, section, conflicting).put("version", version)
                if (version == 7) { input.remove("settings"); input.remove("attachments"); input.put("cloudSchema", "organizer-sync-v3") }
                val before = snapshot(protected)
                val error = runCatching { BackupManager.restore(input.toString(), protected.db, if (conflicting) "ru" else "en") }.exceptionOrNull()
                check(error is IllegalArgumentException) { "Duplicate backup was accepted or failed for an unrelated reason: $error" }
                val message = error.message.orEmpty()
                check(message.contains(if (conflicting) "повторяющиеся идентификаторы" else "duplicate identifiers")) { message }
                check(message.contains(section)) { "Missing invalid section in error: $message" }
                unchanged(before, protected)
            }
        }
    }
    test("Equivalent integral numeric IDs are treated as duplicates") {
        val before = snapshot(protected)
        val input = duplicate(payload, "notes", false).toString().replace("\"id\":1001", "\"id\":1001.0")
        check(runCatching { BackupManager.restore(input, protected.db) }.exceptionOrNull()?.message?.contains("duplicate identifiers") == true)
        unchanged(before, protected)
    }
    test("Duplicate IDs inside ZIP are rejected without changing data or files") {
        val before = snapshot(protected)
        val uri = Uri(File(protected.context.cacheDir, "duplicate.zip").path)
        BackupManager.writeArchiveToUri(protected.context, uri, duplicate(payload, "money", true).toString())
        check(runCatching { BackupManager.restore(BackupManager.readFromUri(protected.context, uri), protected.db) }.isFailure)
        unchanged(before, protected)
    }
    for ((section, table) in tables) for (index in 0..1) {
        test("SQL failure inserting $section row ${index + 1} rolls back every table and preserves attachments; retry succeeds") {
            val target = Fixture("SQL insert target")
            val record = payload.getJSONArray(section).getJSONObject(index)
            val condition = if (section == "projectNoteLinks") "NEW.project_id=${record.getLong("projectId")} AND NEW.note_id=${record.getLong("noteId")}"
                else "NEW.id=${record.getLong("id")}"
            target.db.writableDatabase.execSQL("CREATE TEMP TRIGGER d05_fail BEFORE INSERT ON $table WHEN $condition BEGIN SELECT RAISE(ABORT, 'D05 injected insert failure'); END")
            val before = snapshot(target)
            val error = runCatching { BackupManager.restore(payload.toString(), target.db) }.exceptionOrNull()
            check(error?.message?.contains("D05 injected insert failure") == true) { "SQL failure was hidden: $error" }
            unchanged(before, target)
            target.db.writableDatabase.execSQL("DROP TRIGGER d05_fail")
            BackupManager.restore(payload.toString(), target.db)
            assertRestored(payload, target)
        }
    }
    for ((section, table) in tables) test("SQL failure deleting $section rolls back earlier deletions and leaves attachments unchanged") {
        val target = Fixture("SQL delete target")
        target.db.writableDatabase.execSQL("CREATE TEMP TRIGGER d05_fail BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, 'D05 injected delete failure'); END")
        val before = snapshot(target)
        check(runCatching { BackupManager.restore(payload.toString(), target.db) }.exceptionOrNull()?.message?.contains("D05 injected delete failure") == true)
        unchanged(before, target)
        target.db.writableDatabase.execSQL("DROP TRIGGER d05_fail")
        BackupManager.restore(payload.toString(), target.db)
        assertRestored(payload, target)
    }
    for (section in tables.keys) test("Direct replaceAll rejects duplicate $section and rolls back without JSON validation") {
        val target = Fixture("Direct target")
        val before = snapshot(target)
        check(runCatching { restoreDirect(source.db, target.db, section) }.isFailure) { "Duplicate SQL row was silently ignored" }
        unchanged(before, target)
        // A subsequent write must work: failure must not leave the database in a transaction.
        check(target.db.save(null, "Write after rollback", "Synthetic") > 0)
    }
    test("Valid complete backup allows repeated IDs across sections and shared project/note link components") {
        val target = Fixture("Success target")
        BackupManager.restore(payload.toString(), target.db)
        assertRestored(payload, target)
        check(target.db.getProjectNoteLinks().size == 3)
        check(target.db.getMoneyEntries().any { it.amount == 12.5 })
        check(target.db.save(null, "Next note", "Synthetic") == 1003L)
    }
    val passed = (0 until results.length()).count { results.getJSONObject(it).getString("result") == "PASS" }
    println(JSONObject().put("suite", "D05 restore integrity (synthetic host SQLite)").put("passed", passed)
        .put("total", results.length()).put("cases", results).toString(2))
    check(passed == results.length()) { "D05 regression failures" }
}
