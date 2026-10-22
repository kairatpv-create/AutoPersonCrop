package kz.kairat.organizer

import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs on the previous D14 API surface as a negative control, without new-class references. */
fun main(){
    val source=D06Fixture("probe incoming");val payload=source.backup()
    d06Test("A ZIP with two complete documents must be refused before changing live data"){
        val target=D06Fixture("probe old");val before=d06Snapshot(target)
        val archive=File(target.context.cacheDir,"ambiguous.zip")
        ZipOutputStream(archive.outputStream()).use{z->for(name in listOf("one.json","two.json")){z.putNextEntry(ZipEntry(name));z.write(payload.toString().toByteArray());z.closeEntry()}}
        check(runCatching{BackupManager.restore(BackupManager.readFromUri(target.context,Uri(archive.path)),target.db)}.isFailure){"Reproduced: ambiguous ZIP accepted"}
        d06Unchanged(before,target)
    }
    d06Test("A successful full restore must retain an independent pre-import safety snapshot"){
        val target=D06Fixture("probe safety");target.activate();BackupManager.restore(payload.toString(),target.db)
        check(File(target.context.noBackupFilesDir,"restore-safety").listFiles().orEmpty().any{it.name.startsWith("before-restore-")&&File(it,"organizer.db").isFile&&File(it,"snapshot.json").isFile}){"Reproduced: no pre-import safety snapshot remains"}
    }
    d06Finish("D14 remaining-risk probes (previous D14 sources must fail both)")
}
