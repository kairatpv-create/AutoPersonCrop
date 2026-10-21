package kz.kairat.organizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

internal val d06Results = JSONArray()
internal val d06Sections = listOf("notes", "tasks", "projects", "projectStages", "projectJournal", "projectNoteLinks", "money", "reminders")
internal fun d06Test(name: String, body: () -> Unit) {
    try { body(); d06Results.put(JSONObject().put("name", name).put("result", "PASS")) }
    catch (e: Throwable) {
        d06Results.put(JSONObject().put("name", name).put("result", "FAIL").put("error", e.toString())); e.printStackTrace()
    }
}
internal fun d06Finish(suite: String) {
    val passed = (0 until d06Results.length()).count { d06Results.getJSONObject(it).getString("result") == "PASS" }
    println(JSONObject().put("suite", suite).put("passed", passed).put("total", d06Results.length()).put("cases", d06Results).toString(2))
    check(passed == d06Results.length()) { "D06 regression failures" }
}
internal class D06Fixture(label: String) {
    val context = Context(Files.createTempDirectory("organizer-d06-synthetic-").toFile())
    val db = NoteDatabase(context)
    val settings = SettingsStore(context)
    init {
        AttachmentStore.initialize(context)
        settings.language = "ru"; settings.fontScale = 1.3f; settings.buttonFontScale = 1.15f; settings.boldText = true
        val n = db.save(null, "$label Заметка / Note", "$label full body")
        val n2 = db.save(null, "$label second note", "Other body")
        db.addTask("$label task", "Details")
        val p = db.saveProject(null, "$label project", "Description", "active", 0, 100L, 200L, 987.65, "Client", "Address", "Contact", "red")
        db.addProjectStage(p, "$label stage", 300L); db.addProjectJournal(p, "$label journal"); db.linkNoteToProject(p, n)
        db.addMoneyEntry("expense", 12.5, "Category", p, "$label money")
        db.addMoneyEntry("income", 99.99, "Other", null, "$label unlinked")
        db.addReminder("note", n, 400L, true, false)
        for ((i, bytes) in listOf(byteArrayOf(0, 1, 127, -1) + "$label binary".toByteArray(), "$label Русский / English".toByteArray(), byteArrayOf()).withIndex()) {
            val file = File(context.cacheDir, "synthetic-$i.bin").apply { writeBytes(bytes) }
            AttachmentStore.addLocalFile("note", if (i == 1) n2 else n, file)
        }
        File(context.filesDir, "sentinel.txt").writeText("Keep outside attachment data")
        File(context.filesDir, "attachments/sentinel.txt").writeText("Keep unindexed original file")
    }
    fun activate() { AttachmentStore.initialize(context) }
    fun backup(): JSONObject { activate(); return JSONObject(BackupManager.createBackup(db, settings)) }
}
internal fun d06Files(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }.associate {
    it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
}
internal data class D06Snapshot(val data: JSONObject, val files: Map<String, String>)
internal fun d06Snapshot(f: D06Fixture) = D06Snapshot(f.backup().apply { remove("createdAt") },
    d06Files(f.context.sandbox).filterKeys { !it.startsWith("cache/") })
internal fun d06Unchanged(before: D06Snapshot, target: D06Fixture) {
    val after = d06Snapshot(target)
    check(before.data.similar(after.data)) { "Existing records, settings or attachment index changed" }
    check(before.files == after.files) { "SQLite, attachment bytes, index, sentinel or unindexed original file changed" }
    check(d06WorkDirectories(target).isEmpty()) { "Restore staging was not cleaned up" }
}
internal fun d06WorkDirectories(f: D06Fixture) = f.context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("attachments-restore-") }
internal fun d06Clone(o: JSONObject) = JSONObject(o.toString())
internal fun d06Restored(expected: JSONObject, target: D06Fixture) {
    val actual = target.backup()
    (d06Sections + "attachments").forEach { check(expected.getJSONArray(it).similar(actual.getJSONArray(it))) { "Restore mismatch: $it" } }
    val entries = AttachmentStore.exportBackup()
    for (i in 0 until entries.length()) {
        val o = entries.getJSONObject(i)
        val file = File(target.context.filesDir, "attachments/data/${o.getString("storedName")}")
        check(file.isFile && file.length() == o.getLong("size"))
    }
}
