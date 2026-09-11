package kz.autopersoncrop.batch

import android.content.Context

data class BatchState(
    val folderUri: String = "",
    val running: Boolean = false,
    val paused: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val noPeople: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val currentName: String = "",
    val message: String = "",
)

class BatchStateStore(context: Context) {
    private val p = context.getSharedPreferences("batch_state", Context.MODE_PRIVATE)

    @Synchronized fun read() = BatchState(
        folderUri = p.getString("folder", "") ?: "",
        running = p.getBoolean("running", false),
        paused = p.getBoolean("paused", false),
        total = p.getInt("total", 0),
        completed = p.getInt("completed", 0),
        noPeople = p.getInt("no_people", 0),
        skipped = p.getInt("skipped", 0),
        errors = p.getInt("errors", 0),
        currentName = p.getString("current", "") ?: "",
        message = p.getString("message", "") ?: "",
    )

    @Synchronized fun write(s: BatchState) {
        p.edit()
            .putString("folder", s.folderUri)
            .putBoolean("running", s.running)
            .putBoolean("paused", s.paused)
            .putInt("total", s.total)
            .putInt("completed", s.completed)
            .putInt("no_people", s.noPeople)
            .putInt("skipped", s.skipped)
            .putInt("errors", s.errors)
            .putString("current", s.currentName)
            .putString("message", s.message)
            .commit()
    }

    fun resetForNewFolder(folder: String) = write(BatchState(folderUri = folder, message = "Новая папка выбрана"))
}
