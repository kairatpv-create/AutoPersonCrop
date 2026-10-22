package kz.kairat.organizer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import android.system.Os
import android.system.OsConstants

/** Filesystem operations kept separate so restore failures can be tested deterministically. */
internal open class AttachmentRestoreFiles {
    open fun createWorkDirectory(parent: File): File = Files.createTempDirectory(parent.toPath(), "attachments-restore-").toFile()
    open fun makeDirectory(directory: File) {
        if (!directory.mkdir()) throw IOException("Cannot create attachment restore directory")
    }
    open fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out -> out.write(bytes); out.fd.sync() }
    }
    open fun copyFile(source: File, destination: File) {
        if (destination.exists()) throw IOException("Safety copy destination already exists")
        source.inputStream().use { input -> FileOutputStream(destination).use { output ->
            input.copyTo(output, 8192); output.fd.sync()
        } }
    }
    open fun syncFile(file:File) { java.io.RandomAccessFile(file,"rw").use{it.fd.sync()} }
    open fun move(from: File, to: File) {
        if (to.exists() || !from.renameTo(to)) throw IOException("Cannot move attachment restore directory")
    }
    open fun remove(directory: File) {
        if (!Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
    open fun syncDirectory(directory: File) {
        try {
            if (!directory.isDirectory) throw IOException("Restore sync path is not a directory")
            // O_DIRECTORY is not in Android's public SDK. Paths are private and held under the restore lock.
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (failure: Exception) { throw IOException("Cannot sync attachment restore directory", failure) }
    }
}
