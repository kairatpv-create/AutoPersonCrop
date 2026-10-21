package kz.kairat.organizer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** The SQLite token is committed with the records; file phases alone never decide a DB restore. */
internal object RestoreJournal {
    val lock = Any()
    const val JOURNAL = "organizer-restore.json"
    const val DONE = "organizer-restore.done"
    const val GENERATION = ".organizer-restore-generation"
    const val OWNER = "owner.json"
    private val active = ThreadLocal<String?>()

    class TransactionEndFailed(cause: Throwable) : IOException("Restore transaction requires recovery", cause)
    class RecoveryRequired(message: String, cause: Throwable? = null) : IOException(message, cause)
    data class Intent(val id: String, val workName: String, val database: Boolean, val hadLive: Boolean,
                      val oldGeneration: String?, val settings: JSONObject?)

    fun pending(context: Context): Boolean = File(context.filesDir, JOURNAL).exists() || File(context.filesDir, DONE).exists() ||
        context.filesDir.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("attachments-restore-") }

    fun active(): Boolean = active.get() != null
    fun <T> during(id: String, action: () -> T): T {
        check(!active())
        active.set(id)
        try { return action() } finally { active.remove() }
    }
    fun createStateTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_restore_state(id INTEGER PRIMARY KEY CHECK(id=1),token TEXT NOT NULL)")
    }
    fun committedId(db: SQLiteDatabase): String? {
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='backup_restore_state'", null).use { it.moveToFirst() }
        if (!exists) return null
        return db.rawQuery("SELECT token FROM backup_restore_state WHERE id=1", null).use { if (it.moveToFirst()) it.getString(0) else null }
    }
    fun markCommitted(db: SQLiteDatabase, id: String) {
        check(validId(id))
        db.execSQL("INSERT OR REPLACE INTO backup_restore_state(id,token) VALUES(1,?)", arrayOf(id))
    }

    fun own(work: File, id: String, io: AttachmentRestoreFiles) {
        io.write(File(work, OWNER), JSONObject().put("format", "organizer-restore-owner").put("id", id).toString().toByteArray(Charsets.UTF_8))
        io.syncDirectory(work); io.syncDirectory(work.parentFile!!)
    }
    fun plan(context: Context, work: File, id: String, database: Boolean, settings: JSONObject?): Intent {
        val live = File(context.filesDir, "attachments")
        return Intent(id, work.name, database, live.exists(), generation(live), settings)
    }
    fun begin(context: Context, work: File, intent: Intent, io: AttachmentRestoreFiles) {
        check(!File(context.filesDir, JOURNAL).exists() && !File(context.filesDir, DONE).exists())
        val json = JSONObject().put("format", "organizer-restore-intent").put("version", 1).put("id", intent.id)
            .put("work", work.name).put("database", intent.database).put("hadLive", intent.hadLive)
            .put("oldGeneration", intent.oldGeneration ?: JSONObject.NULL)
        if (intent.settings != null) json.put("settings", intent.settings)
        publish(context, work, JOURNAL, json, io)
    }
    fun publish(context: Context, work: File, name: String, json: JSONObject, io: AttachmentRestoreFiles) {
        val temporary = File(work, "$name.tmp")
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 1024 * 1024) throw IOException("Restore metadata is too large to persist safely")
        io.write(temporary, bytes)
        io.move(temporary, File(context.filesDir, name))
        io.syncDirectory(work); io.syncDirectory(context.filesDir)
    }
    fun moved(context: Context, work: File, io: AttachmentRestoreFiles) {
        io.syncDirectory(work); io.syncDirectory(context.filesDir)
    }

    fun complete(context: Context, intent: Intent, committed: Boolean, io: AttachmentRestoreFiles) {
        val work = work(context, intent.workName)
        if (committed) {
            validateNew(File(context.filesDir, "attachments"), intent.id)
            intent.settings?.let {
                BackupValidation.settings(it, SettingsStore(context).language)
                if (!SettingsStore(context).applyRestoredPreferences(BackupManager.preferences(it))) throw IOException("Cannot finish restored preferences")
            }
        }
        publish(context, work, DONE, JSONObject().put("format", "organizer-restore-done").put("version", 1)
            .put("id", intent.id).put("work", intent.workName).put("committed", committed), io)
        cleanup(context, intent, io)
    }
    private fun cleanup(context: Context, intent: Intent, io: AttachmentRestoreFiles) {
        // Once DONE is durable, cleanup cannot undo a committed restore or replay old preferences.
        try {
            io.remove(work(context, intent.workName)); io.syncDirectory(context.filesDir)
            io.remove(File(context.filesDir, JOURNAL)); io.syncDirectory(context.filesDir)
            io.remove(File(context.filesDir, DONE)); io.syncDirectory(context.filesDir)
        } catch (_: Exception) { /* Retain the completion record and retry cleanup at the next open. */ }
    }

    fun recover(context: Context, db: SQLiteDatabase, io: AttachmentRestoreFiles = AttachmentRestoreFiles()) = synchronized(lock) {
        if (active()) return@synchronized
        try {
            val journal = File(context.filesDir, JOURNAL)
            val done = File(context.filesDir, DONE)
            if (!journal.exists()) {
                if (done.exists()) {
                    val completed = read(done)
                    require(completed.opt("format") == "organizer-restore-done" && completed.opt("version") == 1)
                    require(validId(string(completed, "id")) && completed.opt("committed") is Boolean)
                    require(!work(context, string(completed, "work")).exists())
                    io.remove(done); io.syncDirectory(context.filesDir)
                }
                cleanOrphans(context, null, io)
                return@synchronized
            }
            val intent = intent(read(journal))
            val work = work(context, intent.workName)
            val live = File(context.filesDir, "attachments")
            if (done.exists()) {
                val completed = read(done)
                require(completed.opt("format") == "organizer-restore-done" && completed.opt("version") == 1)
                require(string(completed, "id") == intent.id && string(completed, "work") == intent.workName && completed.opt("committed") is Boolean)
                val expected = if (completed.getBoolean("committed")) intent.id else intent.oldGeneration
                require(generation(live) == expected)
                cleanup(context, intent, io)
                cleanOrphans(context, intent.workName, io)
                return@synchronized
            }
            require(owner(work) == intent.id)
            val staged = File(work, "new"); val previous = File(work, "previous")
            val committed = if (intent.database) committedId(db) == intent.id else generation(live) == intent.id
            if (committed) {
                if (generation(live) != intent.id) {
                    validateNew(staged, intent.id)
                    require(!live.exists() && !Files.isSymbolicLink(live.toPath()))
                    io.move(staged, live); moved(context, work, io)
                }
                validateNew(live, intent.id)
            } else if (previous.exists()) {
                require(generation(previous) == intent.oldGeneration)
                if (live.exists()) {
                    require(generation(live) == intent.id || !live.walkTopDown().any { it.isFile })
                    val displaced = if (staged.exists()) File(work, "displaced") else staged
                    require(!displaced.exists())
                    io.move(live, displaced); moved(context, work, io)
                }
                io.move(previous, live); moved(context, work, io)
            } else if (intent.hadLive) {
                require(live.isDirectory && generation(live) == intent.oldGeneration)
            } else if (live.exists()) {
                require(generation(live) == intent.id || !live.walkTopDown().any { it.isFile })
                require(!staged.exists())
                io.move(live, staged); moved(context, work, io)
            }
            complete(context, intent, committed, io)
            cleanOrphans(context, intent.workName, io)
        } catch (failure: Exception) {
            throw required(context, failure)
        }
    }

    private fun cleanOrphans(context: Context, activeName: String?, io: AttachmentRestoreFiles) {
        for (candidate in context.filesDir.listFiles().orEmpty()) {
            if (!candidate.name.startsWith("attachments-restore-") || candidate.name == activeName) continue
            require(!Files.isSymbolicLink(candidate.toPath()))
            if (!candidate.isDirectory) continue
            // A D06 directory without a journal may contain the only old copy: never guess or delete it.
            require(!File(candidate, "previous").exists())
            if (File(candidate, OWNER).exists()) {
                // A kill during the first marker write leaves only unused preparation files.
                // Keep an unrecognised directory, without preventing access to the intact live set.
                if (runCatching { owner(candidate) }.isSuccess) {
                    io.remove(candidate); io.syncDirectory(context.filesDir)
                }
            }
        }
    }
    fun generation(directory: File): String? {
        require(!Files.isSymbolicLink(directory.toPath()))
        val marker = File(directory, GENERATION)
        if (!marker.exists()) return null
        require(marker.isFile && !Files.isSymbolicLink(marker.toPath()) && marker.length() <= 128)
        return marker.readText(Charsets.UTF_8).also { require(validId(it)) }
    }
    private fun validateNew(directory: File, id: String) {
        require(directory.isDirectory && generation(directory) == id)
        val index = File(directory, "index.json")
        require(index.isFile && !Files.isSymbolicLink(index.toPath()))
        val entries = JSONArray(index.readText(Charsets.UTF_8))
        BackupValidation.attachmentIndex(entries, "en")
        val data = File(directory, "data")
        require(data.isDirectory && !Files.isSymbolicLink(data.toPath()))
        for (i in 0 until entries.length()) {
            val o = entries.getJSONObject(i); val f = File(data, o.getString("storedName"))
            require(f.isFile && !Files.isSymbolicLink(f.toPath()) && f.length() == o.getLong("size"))
        }
    }
    private fun intent(o: JSONObject): Intent {
        require(o.opt("format") == "organizer-restore-intent" && o.opt("version") == 1)
        val id = string(o, "id"); require(validId(id))
        require(o.opt("database") is Boolean && o.opt("hadLive") is Boolean && o.has("oldGeneration"))
        val old = if (o.isNull("oldGeneration")) null else string(o, "oldGeneration").also { require(validId(it)) }
        val settings = if (o.has("settings")) (o.opt("settings") as? JSONObject ?: error("Invalid preferences")) else null
        if (settings != null) BackupValidation.settings(settings, "en")
        return Intent(id, string(o, "work"), o.getBoolean("database"), o.getBoolean("hadLive"), old, settings)
    }
    private fun owner(directory: File): String {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val o = read(File(directory, OWNER))
        require(o.opt("format") == "organizer-restore-owner")
        return string(o, "id").also { require(validId(it)) }
    }
    private fun work(context: Context, name: String): File {
        require(name.startsWith("attachments-restore-") && name.matches(Regex("[A-Za-z0-9_-]+")))
        return File(context.filesDir, name).also { require(it.canonicalFile.parentFile == context.filesDir.canonicalFile && !Files.isSymbolicLink(it.toPath())) }
    }
    private fun read(file: File): JSONObject {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= 1024 * 1024)
        return JSONObject(file.readText(Charsets.UTF_8))
    }
    private fun string(o: JSONObject, key: String) = (o.opt(key) as? String) ?: error("Invalid journal field: $key")
    private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun required(context: Context, cause: Throwable) = RecoveryRequired(if (AppStrings.normalizeLanguage(SettingsStore(context).language) == "ru")
        "Не удалось безопасно завершить восстановление. Данные и резервные файлы сохранены. Повторите запуск приложения."
        else "Recovery could not be completed safely. Data and recovery files have been retained. Please restart the app.", cause)
}
