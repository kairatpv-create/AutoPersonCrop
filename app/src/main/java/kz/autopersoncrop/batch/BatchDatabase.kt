package kz.autopersoncrop.batch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kz.autopersoncrop.io.SourcePhoto

/** Persistent queue for very large batches. */
class BatchDatabase(context: Context) : SQLiteOpenHelper(context, "autocrop_queue.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE queue (
                folder TEXT NOT NULL,
                uri TEXT NOT NULL,
                name TEXT NOT NULL,
                rel TEXT NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                message TEXT NOT NULL DEFAULT '',
                seen INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(folder, uri)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_queue_folder_status ON queue(folder, status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE queue ADD COLUMN seen INTEGER NOT NULL DEFAULT 1") }
        }
        // Version 3 only introduces the PROCESSING state; schema stays compatible.
    }

    /**
     * Synchronize the saved queue with the current scan without discarding completed work.
     * Entries no longer present in the selected tree are removed; existing entries keep status.
     */
    fun sync(folder: String, photos: List<SourcePhoto>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE queue SET seen=0 WHERE folder=?", arrayOf(folder))
            for (p in photos) {
                val inserted = ContentValues().apply {
                    put("folder", folder)
                    put("uri", p.uri.toString())
                    put("name", p.name)
                    put("rel", p.relativeDir)
                    put("status", PENDING)
                    put("message", "")
                    put("seen", 1)
                }
                val row = db.insertWithOnConflict("queue", null, inserted, SQLiteDatabase.CONFLICT_IGNORE)
                if (row == -1L) {
                    val update = ContentValues().apply {
                        put("name", p.name)
                        put("rel", p.relativeDir)
                        put("seen", 1)
                    }
                    db.update("queue", update, "folder=? AND uri=?", arrayOf(folder, p.uri.toString()))
                }
            }
            db.delete("queue", "folder=? AND seen=0", arrayOf(folder))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun status(folder: String, uri: String): Int? = readableDatabase.query(
        "queue", arrayOf("status"), "folder=? AND uri=?", arrayOf(folder, uri), null, null, null, "1"
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    fun mark(folder: String, uri: String, status: Int, message: String = "") {
        val v = ContentValues().apply {
            put("status", status)
            put("message", message.take(1000))
        }
        writableDatabase.update("queue", v, "folder=? AND uri=?", arrayOf(folder, uri))
    }

    fun counts(folder: String): Counts {
        val counts = IntArray(6)
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM queue WHERE folder=? GROUP BY status", arrayOf(folder)
        ).use { c ->
            while (c.moveToNext()) {
                val s = c.getInt(0)
                if (s in counts.indices) counts[s] = c.getInt(1)
            }
        }
        return Counts(
            pending = counts[PENDING],
            done = counts[DONE],
            noPeople = counts[NO_PEOPLE],
            errors = counts[ERROR],
            existing = counts[EXISTING],
            processing = counts[PROCESSING],
        )
    }

    data class Counts(
        val pending: Int,
        val done: Int,
        val noPeople: Int,
        val errors: Int,
        val existing: Int,
        val processing: Int,
    )

    companion object {
        const val PENDING = 0
        const val DONE = 1
        const val NO_PEOPLE = 2
        const val ERROR = 3
        const val EXISTING = 4
        const val PROCESSING = 5
    }
}
