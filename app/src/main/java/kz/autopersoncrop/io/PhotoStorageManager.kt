package kz.autopersoncrop.io

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Finalizes batch output without destroying the only copy of a source photo.
 *
 * Successful crops replace the source document in-place, but the first original is
 * preserved under CROP/ОРИГИНАЛЫ AutoPersonCrop. Photos that were not processed or
 * ended with an error are moved under CROP/НЕОБРАБОТАННЫЕ AutoPersonCrop.
 */
class PhotoStorageManager(
    private val context: Context,
    private val cropRoot: DocumentFile,
) {
    private val resolver get() = context.contentResolver

    fun replaceOriginalWithProcessed(photo: SourcePhoto, tempDir: DocumentFile) {
        val temp = tempDir.findFile(photo.name)
            ?: error("Не найден временный результат ${photo.name}")
        require(temp.isFile) { "Временный результат ${photo.name} не является файлом" }
        require(temp.length() > 0L) { "Временный результат ${photo.name} пуст" }

        val backup = ensureOriginalBackup(photo)
        try {
            copyUri(temp.uri, photo.uri)
            val replaced = DocumentFile.fromSingleUri(context, photo.uri)
            require(replaced != null && replaced.length() > 0L) {
                "Не удалось проверить заменённый файл ${photo.name}"
            }
            resolver.notifyChange(photo.uri, null)
        } catch (t: Throwable) {
            val restoreError = runCatching { copyUri(backup.uri, photo.uri) }.exceptionOrNull()
            if (restoreError != null) {
                throw IllegalStateException(
                    "Не удалось заменить ${photo.name}; восстановление из резерва тоже не удалось: ${restoreError.message}",
                    t,
                )
            }
            throw t
        }

        // Temp is only a staging file. Failure to remove it must not turn a valid crop into an error.
        runCatching { temp.delete() }
    }

    fun moveUnprocessed(photo: SourcePhoto, tempDir: DocumentFile) {
        // PhotoProcessor copies an unchanged file to temp when nobody is found. It is not needed now.
        runCatching { tempDir.findFile(photo.name)?.delete() }

        val destinationDir = ensureArchiveDir(UNPROCESSED_DIR, photo.relativeDir)
        val destination = createUniqueFile(destinationDir, photo.name)
        try {
            val sourceLength = DocumentFile.fromSingleUri(context, photo.uri)?.length() ?: 0L
            copyUri(photo.uri, destination.uri)
            val copiedLength = destination.length()
            require(copiedLength > 0L) { "Не удалось скопировать ${photo.name} в папку необработанных" }
            if (sourceLength > 0L) {
                require(copiedLength == sourceLength) {
                    "Размер копии ${photo.name} не совпадает с исходником"
                }
            }
            val deleted = DocumentsContract.deleteDocument(resolver, photo.uri)
            if (!deleted) error("Не удалось убрать исходный файл ${photo.name} после безопасного копирования")
        } catch (t: Throwable) {
            runCatching { destination.delete() }
            throw t
        }
    }

    private fun ensureOriginalBackup(photo: SourcePhoto): DocumentFile {
        val dir = ensureArchiveDir(ORIGINALS_DIR, photo.relativeDir)
        val existing = dir.findFile(photo.name)?.takeIf { it.isFile && it.length() > 0L }
        if (existing != null) return existing
        dir.findFile(photo.name)?.let { runCatching { it.delete() } }

        val out = dir.createFile("image/jpeg", photo.name)
            ?: error("Не удалось создать резерв оригинала ${photo.name}")
        try {
            val sourceLength = DocumentFile.fromSingleUri(context, photo.uri)?.length() ?: 0L
            copyUri(photo.uri, out.uri)
            val copiedLength = out.length()
            require(copiedLength > 0L) { "Резерв оригинала ${photo.name} пуст" }
            if (sourceLength > 0L) {
                require(copiedLength == sourceLength) {
                    "Резерв оригинала ${photo.name} записан не полностью"
                }
            }
            return out
        } catch (t: Throwable) {
            runCatching { out.delete() }
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

    private fun createUniqueFile(dir: DocumentFile, originalName: String): DocumentFile {
        if (dir.findFile(originalName) == null) {
            return dir.createFile("image/jpeg", originalName)
                ?: error("Не удалось создать $originalName")
        }
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ".jpg"
        for (index in 2..9999) {
            val candidate = "$base ($index)$ext"
            if (dir.findFile(candidate) == null) {
                return dir.createFile("image/jpeg", candidate)
                    ?: error("Не удалось создать $candidate")
            }
        }
        error("Слишком много файлов с именем $originalName")
    }

    private fun copyUri(source: Uri, destination: Uri) {
        resolver.openInputStream(source).use { input ->
            resolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(input) { "Не удалось открыть исходный JPEG" }
                requireNotNull(output) { "Не удалось открыть JPEG для записи" }
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
                output.flush()
            }
        }
    }

    companion object {
        const val ORIGINALS_DIR = "ОРИГИНАЛЫ AutoPersonCrop"
        const val UNPROCESSED_DIR = "НЕОБРАБОТАННЫЕ AutoPersonCrop"
    }
}
