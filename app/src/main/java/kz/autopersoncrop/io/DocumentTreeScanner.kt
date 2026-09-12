package kz.autopersoncrop.io

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque

data class SourcePhoto(
    val uri: Uri,
    val name: String,
    val mime: String,
    val relativeDir: String,
)

class DocumentTreeScanner(private val context: Context) {
    private val accepted = setOf("image/jpeg", "image/jpg")
    private val outputDirCache = HashMap<String, DocumentFile>()

    /**
     * Fast SAF scan. DocumentFile.listFiles()/isFile/name/type can trigger many provider
     * round-trips. Querying each directory cursor directly reads all required columns in one go.
     */
    fun scan(treeUri: Uri): Pair<DocumentFile, List<SourcePhoto>> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Не удалось открыть выбранную папку")
        require(root.canRead()) { "Нет доступа на чтение папки" }

        val output = root.findFile(OUTPUT_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(OUTPUT_DIR)
            ?: error("Не удалось создать папку $OUTPUT_DIR")

        outputDirCache.clear()
        outputDirCache[cacheKey(output, "")] = output

        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val photos = ArrayList<SourcePhoto>(1024)
        val queue = ArrayDeque<DirNode>()
        queue.add(DirNode(rootId, ""))

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol)?.lowercase().orEmpty()

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name.equals(OUTPUT_DIR, ignoreCase = true)) continue
                        val childRel = if (node.relative.isBlank()) name else "${node.relative}/$name"
                        queue.add(DirNode(docId, childRel))
                        continue
                    }

                    val lower = name.lowercase()
                    if (mime !in accepted && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    photos += SourcePhoto(
                        uri = docUri,
                        name = name,
                        mime = mime.ifBlank { "image/jpeg" },
                        relativeDir = node.relative,
                    )
                }
            }
        }

        return output to photos
    }

    fun ensureOutputDir(rootOutput: DocumentFile, relativeDir: String): DocumentFile {
        if (relativeDir.isBlank()) return rootOutput
        val fullKey = cacheKey(rootOutput, relativeDir)
        outputDirCache[fullKey]?.let { return it }

        var current = rootOutput
        var built = ""
        for (part in relativeDir.split('/').filter { it.isNotBlank() }) {
            built = if (built.isBlank()) part else "$built/$part"
            val key = cacheKey(rootOutput, built)
            current = outputDirCache[key] ?: (
                current.findFile(part)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(part)
                    ?: error("Не удалось создать CROP/$relativeDir")
                ).also { outputDirCache[key] = it }
        }
        return current
    }

    private fun cacheKey(rootOutput: DocumentFile, relative: String) =
        "${rootOutput.uri}|$relative"

    private data class DirNode(val documentId: String, val relative: String)

    companion object { const val OUTPUT_DIR = "CROP" }
}
