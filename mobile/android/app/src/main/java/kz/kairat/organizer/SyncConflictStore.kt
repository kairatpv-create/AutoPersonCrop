package kz.kairat.organizer

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Retain both valid sides of a conflict locally, with checksums, without changing the active database. */
internal object SyncConflictStore {
    private fun root(c:Context)=File(c.noBackupFilesDir,"sync-conflicts")
    fun preserve(c:Context,owner:String,local:String,remote:String,base:String?,rev:Long){
        val directory=root(c);val io=AttachmentRestoreFiles()
        if(!directory.exists())io.makeDirectory(directory)
        require(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        val id=SyncSnapshot.hash(owner+"\u0000"+local+"\u0000"+remote)
        val ready=File(directory,id)
        if(!ready.exists()){
            val stage=java.nio.file.Files.createTempDirectory(directory.toPath(),".pending-").toFile()
            try{
                io.write(File(stage,"local.json"),local.toByteArray(Charsets.UTF_8))
                io.write(File(stage,"remote.json"),remote.toByteArray(Charsets.UTF_8))
                base?.let{io.write(File(stage,"base.json"),it.toByteArray(Charsets.UTF_8))}
                val manifest=JSONObject().put("version",1).put("owner",owner).put("revision",rev)
                    .put("local",SyncSnapshot.hash(local)).put("remote",SyncSnapshot.hash(remote))
                base?.let{manifest.put("base",SyncSnapshot.hash(it))}
                io.write(File(stage,"manifest.json"),manifest.toString().toByteArray(Charsets.UTF_8))
                io.syncDirectory(stage);io.move(stage,ready);io.syncDirectory(directory)
            }finally{if(stage.exists())io.remove(stage)}
        }
        val pointer=File.createTempFile("latest-",".tmp",directory)
        try{io.write(pointer,id.toByteArray());check(pointer.renameTo(File(directory,"latest")));io.syncDirectory(directory);io.syncDirectory(c.noBackupFilesDir)}finally{pointer.delete()}
    }
    fun read(c:Context,owner:String,remote:Boolean,lang:String):String{
        val directory=root(c);val id=File(directory,"latest").readText();require(id.matches(Regex("[a-f0-9]{64}")))
        val ready=File(directory,id);val manifest=JSONObject(File(ready,"manifest.json").readText());require(manifest.getInt("version")==1&&manifest.getString("owner")==owner)
        val name=if(remote)"remote" else "local";val file=File(ready,"$name.json");require(file.length()<=850000)
        val payload=SyncSnapshot.parse(file.readText(),lang);require(SyncSnapshot.hash(payload)==manifest.getString(name));return payload
    }
}
