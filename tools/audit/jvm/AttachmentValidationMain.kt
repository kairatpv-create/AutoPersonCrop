package kz.kairat.organizer

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun main() {
    val source = D06Fixture("incoming")
    val payload = source.backup()
    fun reject(name: String, direct: Boolean = false, change: D06Fixture.(JSONArray) -> Unit) = d06Test(name) {
        val protected = D06Fixture("existing")
        val input = d06Clone(payload)
        val array = input.getJSONArray("attachments"); protected.change(array)
        val before = d06Snapshot(protected)
        val failure = runCatching {
            if (direct) AttachmentStore.restoreBackup(array) else BackupManager.restore(input.toString(), protected.db, "ru")
        }.exceptionOrNull()
        check(failure != null) { "Invalid attachment restore reported success" }
        check(failure.message.orEmpty().contains(if (direct) "data has not been changed" else "Данные не изменены")) { failure.toString() }
        d06Unchanged(before, protected)
    }
    val badNames = listOf("", " ", ".", "..", "../sentinel.txt", "../../sentinel.txt", "nested/file.bin", "nested/../file.bin",
        "..\\sentinel.txt", "\\sentinel.txt", "C:\\sentinel.txt", "C:sentinel.txt", "file\u0000.bin", "file\n.bin", "file\r.bin", "file\t.bin", "file\u007f.bin")
    for ((i, name) in badNames.withIndex()) for (direct in listOf(false, true)) {
        reject("Unsafe filename #$i rejected ${if (direct) "directly" else "before database restore"}", direct) { it.getJSONObject(0).put("storedName", name) }
    }
    for (direct in listOf(false, true)) {
        reject("Absolute filename rejected ${if (direct) "directly" else "before database restore"}", direct) {
            it.getJSONObject(0).put("storedName", File(context.filesDir, "sentinel.txt").absolutePath)
        }
        reject("Invalid Base64 ${if (direct) "direct" else "full backup"}", direct) { it.getJSONObject(1).put("data", "!invalid-base64!") }
        reject("Truncated file bytes ${if (direct) "direct" else "full backup"}", direct) { it.getJSONObject(0).put("data", "AA==") }
        reject("Negative size ${if (direct) "direct" else "full backup"}", direct) { it.getJSONObject(0).put("size", -1) }
        reject("Duplicate storedName with distinct IDs ${if (direct) "direct" else "full backup"}", direct) {
            it.getJSONObject(1).put("storedName", it.getJSONObject(0).getString("storedName"))
        }
        reject("Missing file data ${if (direct) "direct" else "full backup"}", direct) { it.getJSONObject(0).remove("data") }
        reject("Wrong metadata type ${if (direct) "direct" else "full backup"}", direct) { it.getJSONObject(0).put("targetId", "1") }
        reject("Wrong file data type ${if (direct) "direct" else "full backup"}", direct) { it.getJSONObject(0).put("data", JSONObject()) }
        reject("Non-object attachment ${if (direct) "direct" else "full backup"}", direct) { it.put(0, false) }
    }
    reject("Real filesystem filename-length error preserves database and old files") { it.getJSONObject(0).put("storedName", "x".repeat(300)) }
    d06Test("ZIP with traversal filename is rejected before database/files change") {
        val protected = D06Fixture("existing ZIP target")
        val before = d06Snapshot(protected)
        val bad = d06Clone(payload).apply { getJSONArray("attachments").getJSONObject(0).put("storedName", "../../sentinel.txt") }
        val uri = Uri(File(protected.context.cacheDir, "unsafe.zip").path)
        BackupManager.writeArchiveToUri(protected.context, uri, bad.toString())
        check(runCatching { BackupManager.restore(BackupManager.readFromUri(protected.context, uri), protected.db) }.isFailure)
        d06Unchanged(before, protected)
    }
    for (version in listOf(3, 7)) d06Test("Unsafe filename is rejected in schema $version") {
        val protected = D06Fixture("existing schema target")
        val before = d06Snapshot(protected)
        val bad = d06Clone(payload).put("version", version)
        bad.getJSONArray("attachments").getJSONObject(0).put("storedName", "../../sentinel.txt")
        check(runCatching { BackupManager.restore(bad.toString(), protected.db) }.isFailure)
        d06Unchanged(before, protected)
    }
    for (archived in listOf(false, true)) d06Test("Complete ${if (archived) "ZIP" else "JSON"} restores binary, Unicode and zero-byte files") {
        val target = D06Fixture("valid target")
        val good = d06Clone(payload)
        good.getJSONArray("attachments").getJSONObject(1).put("storedName", "Фото 100%_русский.bin")
        val uri = Uri(File(target.context.cacheDir, if (archived) "good.zip" else "good.json").path)
        if (archived) BackupManager.writeArchiveToUri(target.context, uri, good.toString()) else BackupManager.writeToUri(target.context, uri, good.toString())
        BackupManager.restore(BackupManager.readFromUri(target.context, uri), target.db)
        d06Restored(good, target); check(d06WorkDirectories(target).isEmpty())
    }
    d06Test("Direct valid attachment restore preserves database and writes every attachment") {
        val target = D06Fixture("direct target")
        val before = target.backup()
        AttachmentStore.restoreBackup(payload.getJSONArray("attachments"))
        val actual = target.backup()
        d06Sections.forEach { check(before.getJSONArray(it).similar(actual.getJSONArray(it))) }
        check(payload.getJSONArray("attachments").similar(actual.getJSONArray("attachments")))
    }
    for (version in 3..5) d06Test("Legacy file schema $version accepts absent duration and restores attachments") {
        val target = D06Fixture("legacy target")
        val legacy = d06Clone(payload).put("version", version)
        val array = legacy.getJSONArray("attachments")
        for (i in 0 until array.length()) array.getJSONObject(i).remove("durationMs")
        BackupManager.restore(legacy.toString(), target.db)
        d06Restored(payload, target)
    }
    d06Test("A valid empty attachment array intentionally clears old attachments") {
        val target = D06Fixture("empty target")
        val empty = d06Clone(payload).put("attachments", JSONArray())
        BackupManager.restore(empty.toString(), target.db)
        d06Restored(empty, target); check(d06WorkDirectories(target).isEmpty())
    }
    d06Test("Cloud v7 without attachments preserves every local attachment byte and unindexed file") {
        val target = D06Fixture("cloud target")
        val old = d06Files(File(target.context.filesDir, "attachments"))
        val cloud = d06Clone(payload).put("version", 7).put("createdAt", 0).put("cloudSchema", "organizer-sync-v3")
        cloud.remove("attachments"); cloud.remove("settings")
        BackupManager.restore(cloud.toString(), target.db)
        check(old == d06Files(File(target.context.filesDir, "attachments")))
        check(d06WorkDirectories(target).isEmpty())
    }
    d06Finish("D06 attachment validation (synthetic host SQLite)")
}
