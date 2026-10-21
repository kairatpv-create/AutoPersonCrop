package kz.kairat.organizer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/** Filesystem operations kept separate so restore failures can be tested deterministically. */
internal open class AttachmentRestoreFiles {
    open fun createWorkDirectory(parent: File): File = Files.createTempDirectory(parent.toPath(), "attachments-restore-").toFile()
    open fun makeDirectory(directory: File) {
        if (!directory.mkdir()) throw IOException("Cannot create attachment restore directory")
    }
    open fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out -> out.write(bytes); out.fd.sync() }
    }
    open fun move(from: File, to: File) {
        if (to.exists() || !from.renameTo(to)) throw IOException("Cannot move attachment restore directory")
    }
    open fun remove(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("Cannot remove attachment restore directory")
    }
}
