package kz.kairat.organizer

data class Note(val id: Long, val title: String, val body: String, val createdAt: Long, val updatedAt: Long, val colorKey: String = "blue")
data class TaskItem(val id: Long, val title: String, val details: String, val done: Boolean, val createdAt: Long, val updatedAt: Long)

data class Project(
    val id: Long,
    val name: String,
    val description: String,
    val status: String = "planned",
    val progress: Int = 0,
    val startAt: Long = 0L,
    val dueAt: Long = 0L,
    val budget: Double = 0.0,
    val client: String = "",
    val address: String = "",
    val contact: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val colorKey: String = "blue"
)

data class ProjectStage(
    val id: Long,
    val projectId: Long,
    val title: String,
    val done: Boolean,
    val dueAt: Long,
    val createdAt: Long
)

data class ProjectJournalEntry(val id: Long, val projectId: Long, val text: String, val createdAt: Long)
data class ProjectNoteLink(val projectId: Long, val noteId: Long)

data class MoneyEntry(val id: Long, val type: String, val amount: Double, val category: String, val projectId: Long?, val projectName: String, val note: String, val createdAt: Long, val colorKey: String = "blue")
data class ReminderEntry(
    val id: Long, val targetType: String, val targetId: Long, val triggerAt: Long,
    val sound: Boolean, val vibrate: Boolean, val soundKey: String, val createdAt: Long,
    val title: String, val details: String,
    val scheduleType: String = "once", // once | weekly
    val daysMask: Int = 0,             // Monday bit 0 ... Sunday bit 6
    val dayMode: String = "single",   // single | all_day
    val baseMinutes: Int = 9 * 60      // scheduled time of day
)
data class AttachmentEntry(
    val id: Long,
    val targetType: String,
    val targetId: Long,
    val displayName: String,
    val storedName: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val durationMs: Long = 0L
) {
    val isVoice: Boolean get() = mimeType.startsWith("audio/")
}

data class RestoredPreferences(
    val darkTheme: Boolean?,
    val fontScale: Float?,
    val backupMode: String,
    val email: String,
    val boldText: Boolean?,
    val language: String? = null,
    val projectsEnabled: Boolean? = null,
    val buttonFontScale: Float? = null
)
