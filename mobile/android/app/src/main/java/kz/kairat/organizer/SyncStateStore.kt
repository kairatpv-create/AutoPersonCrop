package kz.kairat.organizer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** One durable owner and acknowledgement per local database. Survives logout; never exported. */
internal class SyncStateStore(context:Context,private val lang:String) {
    data class State(val owner:String,val email:String,val payload:String?,val revision:Long)
    // Non-secret provenance must travel with Android's system backup of the database.
    // Baselines/tokens are different: losing the baseline must never erase the owner.
    private val provenance=context.getSharedPreferences("organizer_sync_owner",Context.MODE_PRIVATE)
    private val directory=File(context.noBackupFilesDir,"sync-state")
    private val file=File(directory,"state.json")
    fun read():State? {
        val owner=provenance.getString("owner","").orEmpty()
        val email=provenance.getString("email","").orEmpty()
        require(owner.isBlank()==email.isBlank()) { "Incomplete sync ownership" }
        if(!file.exists())return if(owner.isBlank())null else State(owner,email,null,0)
        require(!Files.isSymbolicLink(file.toPath())&&file.length()<=2000000){"Invalid sync state"}
        val o=JSONObject(file.readText())
        require(o.getInt("version")==1)
        val payload=if(o.has("payload"))SyncSnapshot.parse(o.getString("payload"),lang) else null
        require(o.getString("owner").isNotBlank()&&o.getString("email").isNotBlank()&&o.getLong("revision")>=0)
        if(payload!=null)require(o.getString("sha256")==SyncSnapshot.hash(payload)){"Damaged sync acknowledgement"}
        if(owner.isNotBlank())require(owner==o.getString("owner")&&email==o.getString("email")){"Conflicting sync ownership"}
        return State(o.getString("owner"),o.getString("email"),payload,o.getLong("revision"))
    }
    fun write(state:State) {
        val owner=provenance.getString("owner","").orEmpty();val email=provenance.getString("email","").orEmpty()
        require(owner.isBlank()||owner==state.owner&&email==state.email){"Cannot change the owner of local data"}
        check(provenance.edit().putString("owner",state.owner).putString("email",state.email).commit()) { "Could not retain sync ownership" }
        if(!directory.exists())check(directory.mkdir())
        require(Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))
        val io=AttachmentRestoreFiles()
        val o=JSONObject().put("version",1).put("owner",state.owner).put("email",state.email).put("revision",state.revision)
        state.payload?.let{o.put("payload",it).put("sha256",SyncSnapshot.hash(it))}
        val temporary=File.createTempFile("state-",".tmp",directory)
        try{
            io.write(temporary,o.toString().toByteArray(Charsets.UTF_8))
            check(temporary.renameTo(file)){"Could not publish sync state"}
            io.syncDirectory(directory);io.syncDirectory(directory.parentFile!!)
        }finally{temporary.delete()}
    }
}
