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
    private val outputFilesCache = HashMap<String, Map<String, Uri>>()

    fun scan(treeUri: Uri): Pair<DocumentFile, List<SourcePhoto>> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Не удалось открыть выбранную папку")
        require(root.canRead()) { "Нет доступа на чтение папки" }

        val output = root.findFile(OUTPUT_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(OUTPUT_DIR)
            ?: error("Не удалось создать папку $OUTPUT_DIR")

        outputDirCache.clear()
        outputFilesCache.clear()
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
                    photos += SourcePhoto(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
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

    /**
     * Index one output directory once. This avoids DocumentFile.findFile(name) for every image,
     * which becomes extremely expensive when CROP already contains hundreds or thousands of files.
     */
    fun existingOutputUri(outputDir: DocumentFile, name: String): Uri? {
        val key = outputDir.uri.toString()
        val index = outputFilesCache.getOrPut(key) { readOutputIndex(outputDir.uri) }
        return index[name]
    }

    private fun readOutputIndex(dirUri: Uri): Map<String, Uri> {
        val parentId = DocumentsContract.getDocumentId(dirUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val map = HashMap<String, Uri>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                map[name] = DocumentsContract.buildDocumentUriUsingTree(dirUri, id)
            }
        }
        return map
    }

    private fun cacheKey(rootOutput: DocumentFile, relative: String) =
        "${rootOutput.uri}|$relative"

    private data class DirNode(val documentId: String, val relative: String)

    companion object { const val OUTPUT_DIR = "CROP" }
}
