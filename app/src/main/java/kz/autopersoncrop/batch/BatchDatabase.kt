package kz.autopersoncrop.batch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kz.autopersoncrop.io.SourcePhoto

/** Persistent queue for very large batches. */
class BatchDatabase(context: Context) : SQLiteOpenHelper(context, "autocrop_queue.db", null, 4) {
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
                sort_index INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(folder, uri)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_queue_folder_status ON queue(folder, status)")
        db.execSQL("CREATE INDEX idx_queue_folder_sort ON queue(folder, sort_index)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE queue ADD COLUMN seen INTEGER NOT NULL DEFAULT 1") }
        }
        if (oldVersion < 4) {
            runCatching { db.execSQL("ALTER TABLE queue ADD COLUMN sort_index INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("CREATE INDEX idx_queue_folder_sort ON queue(folder, sort_index)") }
        }
    }

    fun sync(folder: String, photos: List<SourcePhoto>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE queue SET seen=0 WHERE folder=?", arrayOf(folder))

            val insert = db.compileStatement(
                "INSERT OR IGNORE INTO queue(folder,uri,name,rel,status,message,seen,sort_index) VALUES(?,?,?,?,0,'',1,?)"
            )
            val update = db.compileStatement(
                "UPDATE queue SET name=?, rel=?, seen=1, sort_index=? WHERE folder=? AND uri=?"
            )
            try {
                photos.forEachIndexed { index, p ->
                    val uri = p.uri.toString()
                    insert.clearBindings()
                    insert.bindString(1, folder)
                    insert.bindString(2, uri)
                    insert.bindString(3, p.name)
                    insert.bindString(4, p.relativeDir)
                    insert.bindLong(5, index.toLong())
                    val row = insert.executeInsert()
                    if (row == -1L) {
                        update.clearBindings()
                        update.bindString(1, p.name)
                        update.bindString(2, p.relativeDir)
                        update.bindLong(3, index.toLong())
                        update.bindString(4, folder)
                        update.bindString(5, uri)
                        update.executeUpdateDelete()
                    }
                }
            } finally {
                insert.close()
                update.close()
            }

            db.delete("queue", "folder=? AND seen=0", arrayOf(folder))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Mark every current item in this folder pending so a new crop algorithm can be tested. */
    fun resetFolder(folder: String) {
        val v = ContentValues().apply {
            put("status", PENDING)
            put("message", "")
        }
        writableDatabase.update("queue", v, "folder=?", arrayOf(folder))
    }

    fun hasRecoverable(folder: String): Boolean {
        if (folder.isBlank()) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM queue WHERE folder=? AND status IN (?,?,?) LIMIT 1",
            arrayOf(folder, PENDING.toString(), ERROR.toString(), PROCESSING.toString()),
        ).use { c -> return c.moveToFirst() }
    }

    fun status(folder: String, uri: String): Int? = readableDatabase.query(
        "queue", arrayOf("status"), "folder=? AND uri=?", arrayOf(folder, uri), null, null, null, "1"
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    fun statuses(folder: String): HashMap<String, Int> {
        val result = HashMap<String, Int>()
        readableDatabase.query(
            "queue", arrayOf("uri", "status"), "folder=?", arrayOf(folder), null, null, "sort_index ASC"
        ).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = c.getInt(1)
        }
        return result
    }

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
    ) {
        val finished: Int get() = done + noPeople + existing
        val unresolved: Int get() = pending + processing + errors
    }

    companion object {
        const val PENDING = 0
        const val DONE = 1
        const val NO_PEOPLE = 2
        const val ERROR = 3
        const val EXISTING = 4
        const val PROCESSING = 5
    }
}
