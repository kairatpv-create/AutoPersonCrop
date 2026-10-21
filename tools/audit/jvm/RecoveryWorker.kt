package kz.kairat.organizer

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Separate JVM. halt(73) bypasses Kotlin finally blocks and SQLite connection shutdown. */
internal object RecoveryWorker {
    @JvmStatic fun main(args:Array<String>){
        val context=Context(File(args[0]));val mode=args[1]
        if(mode=="blocked"){
            val failure=runCatching{AttachmentStore.initialize(context);NoteDatabase(context).getNotes()}.exceptionOrNull()
            check(failure is java.io.IOException){"Unsafe recovery was not blocked: $failure"}
            check(failure.message.orEmpty().contains("retained")||failure.message.orEmpty().contains("сохранены"))
            println("BLOCKED");return
        }
        AttachmentStore.initialize(context)
        val db=NoteDatabase(context)
        if(mode=="restore"||mode=="files"){
            val payload=JSONObject(File(args[2]).readText());val phase=args[3]
            if(phase=="after-sql-commit")System.setProperty("organizer.audit.afterCommit","halt")
            val io=object:AttachmentRestoreFiles(){
                override fun write(file:File,bytes:ByteArray){
                    super.write(file,bytes)
                    if(phase=="stage-owner"&&file.name=="owner.json" || phase=="stage-data"&&file.parentFile.name=="data" || phase=="stage-index"&&file.name=="index.json")Runtime.getRuntime().halt(73)
                }
                override fun move(from:File,to:File){
                    super.move(from,to)
                    if(phase=="intent"&&to.name=="organizer-restore.json" || phase=="old-move"&&to.name=="previous" || phase=="new-move"&&to.name=="attachments" || phase=="done"&&to.name=="organizer-restore.done")Runtime.getRuntime().halt(73)
                }
                override fun remove(directory:File){
                    if(phase=="cleanup-partial"&&directory.name.startsWith("attachments-restore-")){
                        File(directory,"previous/data").listFiles().orEmpty().firstOrNull()?.delete()
                        Runtime.getRuntime().halt(73)
                    }
                    super.remove(directory)
                    if(phase=="cleanup-work"&&directory.name.startsWith("attachments-restore-"))Runtime.getRuntime().halt(73)
                    if(phase=="cleanup-journal"&&directory.name=="organizer-restore.json")Runtime.getRuntime().halt(73)
                    if(phase=="cleanup-done"&&directory.name=="organizer-restore.done")Runtime.getRuntime().halt(73)
                }
            }
            if(mode=="files")AttachmentStore.withPreparedBackup(payload.getJSONArray("attachments"),"en",io){it()}
            else BackupManager.restore(payload.toString(),db,"en",io)
            error("Requested process-death phase was not reached: $phase")
        }
        check(mode=="snapshot")
        val backup=JSONObject(BackupManager.createBackup(db,SettingsStore(context))).apply{remove("createdAt")}
        val prefs=JSONObject(File(context.sandbox,"shared_prefs/organizer_settings.json").readText())
        val protected=JSONObject()
        prefs.keys().forEach{key->if(key.startsWith("pin_")||key.startsWith("sync_")||key=="last_backup_at")protected.put(key,prefs.get(key))}
        val files=d06Files(File(context.filesDir,"attachments")).filterKeys{it!=".organizer-restore-generation"}
        val pending=context.filesDir.listFiles().orEmpty().count{it.name.startsWith("attachments-restore-")||it.name=="organizer-restore.json"||it.name=="organizer-restore.done"}
        db.close()
        println(JSONObject().put("backup",backup).put("files",JSONObject(files)).put("protected",protected)
            .put("sentinel",File(context.filesDir,"sentinel.txt").readText()).put("pending",pending))
    }
}
