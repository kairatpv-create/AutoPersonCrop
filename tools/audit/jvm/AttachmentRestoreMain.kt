package kz.kairat.organizer

import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private class FaultFiles(private val phase: String) : AttachmentRestoreFiles() {
    private var writes = 0
    private var moves = 0
    override fun createWorkDirectory(parent: File): File {
        if (phase == "create-work") throw IOException("D06 injected create failure")
        if (phase == "create-security") throw SecurityException("D06 injected access failure")
        return super.createWorkDirectory(parent)
    }
    override fun makeDirectory(directory: File) {
        if (phase == "mkdir-${directory.name}") throw IOException("D06 injected mkdir failure")
        super.makeDirectory(directory)
    }
    override fun write(file: File, bytes: ByteArray) {
        writes++
        if (phase == "write-$writes" || phase == "partial-$writes") {
            if (phase.startsWith("partial")) file.writeBytes(bytes.take(1).toByteArray())
            throw IOException("D06 injected write failure")
        }
        super.write(file, bytes)
    }
    override fun move(from: File, to: File) {
        moves++
        if (phase == "move-$moves" || phase == "rollback-fails" && moves in 2..3) throw IOException("D06 injected rename failure")
        super.move(from, to)
    }
    override fun remove(directory: File) {
        if (phase == "cleanup") throw IOException("D06 injected cleanup failure")
        super.remove(directory)
    }
}

fun main() {
    val source = D06Fixture("incoming")
    val payload = source.backup()
    for (phase in listOf("create-work", "create-security", "mkdir-new", "mkdir-data", "write-1", "write-2", "write-3", "write-4",
        "partial-1", "partial-2", "partial-4", "move-1", "move-2")) {
        d06Test("$phase failure preserves the database/index/files; a complete retry succeeds") {
            val target = D06Fixture("fault target")
            val before = d06Snapshot(target)
            val failure = runCatching { BackupManager.restore(payload.toString(), target.db, "ru", FaultFiles(phase)) }.exceptionOrNull()
            check(failure is IOException && failure.message.orEmpty().contains("Данные не изменены")) { "Failure was hidden or misreported: $failure" }
            d06Unchanged(before, target)
            BackupManager.restore(payload.toString(), target.db)
            d06Restored(payload, target)
        }
    }
    d06Test("Failure after file promotion rolls back both real SQLite replacement and original attachment directory") {
        val target = D06Fixture("post-promotion target")
        val before = d06Snapshot(target)
        val sourceDb = source.db
        val failure = runCatching {
            AttachmentStore.withPreparedBackup(payload.getJSONArray("attachments"), "en", AttachmentRestoreFiles()) { install ->
                target.db.replaceAll(sourceDb.getNotes(), sourceDb.getTasks(), sourceDb.getProjects(), sourceDb.getMoneyEntries(),
                    sourceDb.getReminders(), sourceDb.getAllProjectStages(), sourceDb.getAllProjectJournal(), sourceDb.getProjectNoteLinks(), beforeCommit = {
                        install(); throw IOException("D06 injected failure after file promotion")
                    })
            }
        }.exceptionOrNull()
        check(failure is IOException && failure.message.orEmpty().contains("data has not been changed"))
        d06Unchanged(before, target)
        BackupManager.restore(payload.toString(), target.db); d06Restored(payload, target)
    }
    d06Test("Cleanup failure does not report a committed restore as failed or delete recoverable old files") {
        val target = D06Fixture("cleanup target")
        val original = d06Files(File(target.context.filesDir, "attachments"))
        BackupManager.restore(payload.toString(), target.db, "en", FaultFiles("cleanup"))
        d06Restored(payload, target)
        val work = d06WorkDirectories(target).single()
        check(original == d06Files(File(work, "previous")))
        // Synthetic test cleanup only; the production path deliberately retains the old copy.
        work.deleteRecursively()
    }
    d06Test("A second rename failure during rollback retains all original files for recovery and rolls back SQLite") {
        val target = D06Fixture("rollback target")
        val originalDb = d06Files(target.context.sandbox).getValue("organizer.db")
        val originalFiles = d06Files(File(target.context.filesDir, "attachments"))
        val failure = runCatching { BackupManager.restore(payload.toString(), target.db, "ru", FaultFiles("rollback-fails")) }.exceptionOrNull()
        check(failure is IOException && failure.message.orEmpty().contains("Резервный каталог сохранён"))
        check(originalDb == d06Files(target.context.sandbox).getValue("organizer.db"))
        val work = d06WorkDirectories(target).single()
        val previous = File(work, "previous")
        check(originalFiles == d06Files(previous))
        check(previous.renameTo(File(target.context.filesDir, "attachments"))) // Recover this synthetic fixture.
        work.deleteRecursively()
        check(originalFiles == d06Files(File(target.context.filesDir, "attachments")))
    }
    d06Test("Attachment readers wait during directory replacement and receive the complete committed index") {
        val target = D06Fixture("concurrent target")
        val paused = CountDownLatch(1); val resume = CountDownLatch(1); val readerStarted = CountDownLatch(1); val readerDone = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(); val exported = AtomicReference<JSONArray?>()
        val io = object : AttachmentRestoreFiles() {
            override fun move(from: File, to: File) {
                super.move(from, to)
                if (to.name == "previous") { paused.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            }
        }
        val restore = Thread { try { BackupManager.restore(payload.toString(), target.db, "en", io) } catch (e: Throwable) { error.set(e) } }
        restore.start()
        check(paused.await(5, TimeUnit.SECONDS))
        val reader = Thread {
            readerStarted.countDown()
            try { exported.set(AttachmentStore.exportBackup()) } catch (e: Throwable) { error.set(e) } finally { readerDone.countDown() }
        }
        try {
            reader.start(); check(readerStarted.await(5, TimeUnit.SECONDS))
            check(!readerDone.await(100, TimeUnit.MILLISECONDS)) { "Reader observed the intermediate attachment state" }
        } finally { resume.countDown() }
        restore.join(5000); reader.join(5000)
        check(!restore.isAlive && !reader.isAlive && error.get() == null) { error.get().toString() }
        check(payload.getJSONArray("attachments").similar(exported.get()))
        d06Restored(payload, target)
    }
    d06Finish("D06 staged attachment restore failures (synthetic host SQLite)")
}
