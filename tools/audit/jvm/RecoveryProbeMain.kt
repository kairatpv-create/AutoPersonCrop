package kz.kairat.organizer

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** API-compatible with D06: this test must fail there and pass with the journal. */
fun main(){
    d06Test("Process death after new file promotion must restart with the complete old SQLite/attachment pair"){
        val source=D06Fixture("probe incoming")
        val payload=File(source.context.cacheDir,"probe.json").apply{writeText(source.backup().toString())}
        val target=D06Fixture("probe existing")
        val before=target.backup()
        val files=d06Files(File(target.context.filesDir,"attachments"))
        source.db.close();target.db.close()
        fun run(mode:String):Pair<Int,String>{
            val logs=Files.createTempDirectory("organizer-d14-probe-log-").toFile()
            val out=File(logs,"out");val err=File(logs,"err")
            val process=ProcessBuilder(File(System.getProperty("java.home"),"bin/java").path,"-cp",System.getProperty("java.class.path"),
                "kz.kairat.organizer.RecoveryWorker",target.context.sandbox.path,mode,payload.path,"new-move")
                .redirectOutput(out).redirectError(err).start()
            if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();error("Probe timed out")}
            check(process.exitValue() in listOf(0,73)){err.readText()}
            return process.exitValue() to out.readText()
        }
        check(run("restore").first==73)
        val actual=JSONObject(run("snapshot").second)
        for(section in d06Sections)check(before.getJSONArray(section).similar(actual.getJSONObject("backup").getJSONArray(section))){"Old SQLite records were not recovered"}
        check(before.getJSONArray("attachments").similar(actual.getJSONObject("backup").getJSONArray("attachments"))){"Reproduced: old SQLite records are paired with incoming attachment bytes after a process restart"}
        check(JSONObject(files).similar(actual.getJSONObject("files")))
        check(actual.getInt("pending")==0)
    }
    d06Finish("D14 process-death regression probe (also runs on unmodified D06 source)")
}
