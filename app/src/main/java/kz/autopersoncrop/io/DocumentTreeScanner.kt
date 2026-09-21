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
    val lastModified: Long = 0L,
)

data class ScanResult(
    val outputRoot: DocumentFile,
    val photos: List<SourcePhoto>,
    val samplesByScene: Map<String, List<SourcePhoto>>,
)

class DocumentTreeScanner(private val context: Context) {
    private val accepted = setOf("image/jpeg", "image/jpg")
    private val outputDirCache = HashMap<String, DocumentFile>()
    private val outputFilesCache = HashMap<String, Map<String, Uri>>()

    fun scan(treeUri: Uri): ScanResult {
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
        val samplesByScene = LinkedHashMap<String, MutableList<SourcePhoto>>()
        val queue = ArrayDeque<DirNode>()
        queue.add(DirNode(rootId, "", null))

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol)?.lowercase().orEmpty()

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (node.sampleScene == null && name.equals(OUTPUT_DIR, ignoreCase = true)) continue

                        if (node.sampleScene == null && name.equals(SAMPLE_DIR, ignoreCase = true)) {
                            // Files inside ОБРАЗЕЦ belong to the parent scene and are never added
                            // to the normal processing queue or mirrored to CROP.
                            queue.add(DirNode(docId, node.relative, node.relative))
                        } else if (node.sampleScene != null) {
                            // Allow optional subfolders inside ОБРАЗЕЦ, still tied to one scene.
                            queue.add(DirNode(docId, node.relative, node.sampleScene))
                        } else {
                            val childRel = if (node.relative.isBlank()) name else "${node.relative}/$name"
                            queue.add(DirNode(docId, childRel, null))
                        }
                        continue
                    }

                    val lower = name.lowercase()
                    if (mime !in accepted && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) continue
                    val modified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L
                    val photo = SourcePhoto(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = name,
                        mime = mime.ifBlank { "image/jpeg" },
                        relativeDir = node.relative,
                        lastModified = modified,
                    )

                    if (node.sampleScene != null) {
                        samplesByScene.getOrPut(node.sampleScene) { ArrayList() }.add(photo)
                    } else {
                        photos += photo
                    }
                }
            }
        }

        val comparator = Comparator<SourcePhoto> { a, b ->
            val dir = naturalCompare(a.relativeDir, b.relativeDir)
            if (dir != 0) return@Comparator dir
            val name = naturalCompare(a.name, b.name)
            if (name != 0) return@Comparator name
            a.lastModified.compareTo(b.lastModified)
        }
        photos.sortWith(comparator)
        samplesByScene.values.forEach { it.sortWith(comparator) }

        return ScanResult(
            outputRoot = output,
            photos = photos,
            samplesByScene = samplesByScene.mapValues { it.value.toList() },
        )
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

    fun existingOutputUri(outputDir: DocumentFile, name: String): Uri? {
        val key = outputDir.uri.toString()
        val index = outputFilesCache.getOrPut(key) { readOutputIndex(outputDir.uri) }
        return index[name]
    }

    fun invalidateOutputIndex(outputDir: DocumentFile) {
        outputFilesCache.remove(outputDir.uri.toString())
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

    private fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                var ea = ia
                var eb = ib
                while (ea < a.length && a[ea].isDigit()) ea++
                while (eb < b.length && b[eb].isDigit()) eb++
                val na = a.substring(ia, ea).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(ib, eb).trimStart('0').ifEmpty { "0" }
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val n = na.compareTo(nb)
                if (n != 0) return n
                ia = ea
                ib = eb
                continue
            }
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            ia++
            ib++
        }
        return a.length.compareTo(b.length)
    }

    private fun cacheKey(rootOutput: DocumentFile, relative: String) =
        "${rootOutput.uri}|$relative"

    private data class DirNode(
        val documentId: String,
        val relative: String,
        val sampleScene: String?,
    )

    companion object {
        const val OUTPUT_DIR = "CROP"
        const val SAMPLE_DIR = "ОБРАЗЕЦ"
    }
}
