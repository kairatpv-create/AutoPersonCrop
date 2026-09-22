package kz.autopersoncrop.io

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * Keeps the normal processing path fast: successful results stay in CROP while
 * original photos remain untouched. Only unprocessed/error photos are copied
 * into CROP/НЕОБРАБОТАННЫЕ AutoPersonCrop for manual review.
 */
class PhotoStorageManager(
    private val context: Context,
    private val cropRoot: DocumentFile,
) {
    private val resolver get() = context.contentResolver

    /**
     * Successful PhotoProcessor output is already written to CROP. Nothing else
     * is required here; deliberately avoid any extra copy/read/write operations.
     */
    fun replaceOriginalWithProcessed(
        @Suppress("UNUSED_PARAMETER") photo: SourcePhoto,
        @Suppress("UNUSED_PARAMETER") tempDir: DocumentFile,
    ) = Unit

    /**
     * Copy an unchanged original into the manual-review folder, but never remove
     * or modify the original in its source folder.
     */
    fun moveUnprocessed(photo: SourcePhoto, tempDir: DocumentFile) {
        // No-people processing may have written an unchanged copy into the normal
        // CROP output directory. Remove only that redundant output copy.
        runCatching { tempDir.findFile(photo.name)?.delete() }

        val destinationDir = ensureArchiveDir(UNPROCESSED_DIR, photo.relativeDir)
        destinationDir.findFile(photo.name)?.let { existing ->
            check(existing.delete()) { "Не удалось обновить копию ${photo.name} в папке необработанных" }
        }
        val destination = destinationDir.createFile("image/jpeg", photo.name)
            ?: error("Не удалось создать копию ${photo.name} в папке необработанных")

        try {
            val sourceLength = DocumentFile.fromSingleUri(context, photo.uri)?.length() ?: 0L
            resolver.openInputStream(photo.uri).use { input ->
                resolver.openOutputStream(destination.uri, "w").use { output ->
                    requireNotNull(input) { "Не удалось открыть исходный JPEG" }
                    requireNotNull(output) { "Не удалось открыть JPEG для записи" }
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                }
            }
            val copiedLength = destination.length()
            require(copiedLength > 0L) { "Копия ${photo.name} в папке необработанных пуста" }
            if (sourceLength > 0L) {
                require(copiedLength == sourceLength) {
                    "Размер копии ${photo.name} не совпадает с исходником"
                }
            }
        } catch (t: Throwable) {
            runCatching { destination.delete() }
            throw t
        }
    }

    private fun ensureArchiveDir(kind: String, relativeDir: String): DocumentFile {
        var current = cropRoot.findFile(kind)?.takeIf { it.isDirectory }
            ?: cropRoot.createDirectory(kind)
            ?: error("Не удалось создать папку CROP/$kind")
        for (part in relativeDir.split('/').filter { it.isNotBlank() }) {
            current = current.findFile(part)?.takeIf { it.isDirectory }
                ?: current.createDirectory(part)
                ?: error("Не удалось создать папку $kind/$relativeDir")
        }
        return current
    }

    companion object {
        const val UNPROCESSED_DIR = "НЕОБРАБОТАННЫЕ AutoPersonCrop"
    }
}
