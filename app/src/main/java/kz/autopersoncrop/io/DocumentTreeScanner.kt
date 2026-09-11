package kz.autopersoncrop.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class SourcePhoto(
    val uri: Uri,
    val name: String,
    val mime: String,
    val relativeDir: String,
)

class DocumentTreeScanner(private val context: Context) {
    private val accepted = setOf("image/jpeg", "image/jpg")

    fun scan(treeUri: Uri): Pair<DocumentFile, List<SourcePhoto>> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Не удалось открыть выбранную папку")
        require(root.canRead()) { "Нет доступа на чтение папки" }
        val output = root.findFile(OUTPUT_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(OUTPUT_DIR)
            ?: error("Не удалось создать папку $OUTPUT_DIR")

        val photos = ArrayList<SourcePhoto>()
        walk(root, "", photos)
        photos.sortWith(compareBy<SourcePhoto> { it.relativeDir }.thenBy { it.name.lowercase() })
        return output to photos
    }

    private fun walk(dir: DocumentFile, relative: String, out: MutableList<SourcePhoto>) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                if (f.name.equals(OUTPUT_DIR, ignoreCase = true)) continue
                val childRel = if (relative.isBlank()) f.name.orEmpty() else "$relative/${f.name.orEmpty()}"
                walk(f, childRel, out)
            } else if (f.isFile) {
                val mime = f.type?.lowercase().orEmpty()
                val name = f.name ?: continue
                if (mime in accepted || name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg")) {
                    out += SourcePhoto(f.uri, name, mime.ifBlank { "image/jpeg" }, relative)
                }
            }
        }
    }

    fun ensureOutputDir(rootOutput: DocumentFile, relativeDir: String): DocumentFile {
        var current = rootOutput
        if (relativeDir.isBlank()) return current
        for (part in relativeDir.split('/').filter { it.isNotBlank() }) {
            current = current.findFile(part)?.takeIf { it.isDirectory }
                ?: current.createDirectory(part)
                ?: error("Не удалось создать CROP/$relativeDir")
        }
        return current
    }

    companion object { const val OUTPUT_DIR = "CROP" }
}
