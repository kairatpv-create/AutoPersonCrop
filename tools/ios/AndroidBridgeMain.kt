package kz.kairat.organizer

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Synthetic-only interoperability probe, compiled with the actual Android backup/database code. */
fun main(args:Array<String>) {
    val sandbox=Files.createTempDirectory("organizer-ios-bridge-").toFile()
    try {
        val context=Context(sandbox)
        AttachmentStore.initialize(context)
        val db=NoteDatabase(context)
        val settings=SettingsStore(context)
        if(args[0]=="export") {
            settings.language="ru"; settings.darkTheme=true; settings.boldText=true
            settings.fontScale=1.15f; settings.buttonFontScale=1.1f
            val n=db.save(null,"Заметка / Note 📝","Первая строка\nSecond line — synthetic data")
            val p=db.saveProject(null,"Тестовый проект","Synthetic only","active",35,1700000000000L,1900000000000L,987.65,"Клиент","Test street","test@example.invalid","yellow")
            db.addTask("Проверить копию","Check all eight collections")
            db.addProjectStage(p,"Этап 1",1900000000000L)
            db.addProjectJournal(p,"Тестовая запись журнала")
            db.linkNoteToProject(p,n)
            db.addMoneyEntry("expense",19.95,"Материалы",p,"Synthetic expense","blue")
            db.addMoneyEntry("income",1000.5,"Доход",null,"Synthetic income","green")
            db.addReminder("note",n,1900000000000L,true,true,"system","weekly",85,"single",570)
            val f=File(sandbox,"test.bin").apply{writeBytes(byteArrayOf(0,1,2,127,-128,-1,10,13))}
            AttachmentStore.addLocalFile("note",n,f,"fixture.bin","application/octet-stream")
            val out=File(args[1]).apply{mkdirs()}
            val json=BackupManager.createBackup(db,settings)
            File(out,"android-v6.json").writeText(json)
            val zip=File(sandbox,"fixture.zip")
            ZipOutputStream(zip.outputStream()).use { z->z.putNextEntry(ZipEntry("my_organizer_backup_fixture.json"));z.write(json.toByteArray());z.closeEntry() }
            File(out,"android-v6.zip.base64").writeText(Base64.getEncoder().encodeToString(zip.readBytes())+"\n")
            File(out,"android-v7.json").writeText(BackupManager.createCloudBackup(db,settings))
            println("Exported synthetic fixtures using Android BackupManager, SQLite and AttachmentStore")
        } else {
            val input=File(args[1]).readText()
            BackupManager.restore(input,db,"en")
            val restored=JSONObject(BackupManager.createBackup(db,settings))
            val expected=JSONObject(File(args[2]).readText())
            for(key in listOf("notes","tasks","projects","projectStages","projectJournal","projectNoteLinks","money","reminders","attachments")) {
                val a=expected.getJSONArray(key);val b=restored.getJSONArray(key)
                check(a.length()==b.length()){ "Collection count changed: $key" }
                fun normalized(array:org.json.JSONArray)= (0 until array.length()).map { i->array.getJSONObject(i) }.sortedBy { it.optLong("id",it.optLong("projectId")) }
                normalized(a).zip(normalized(b)).forEach { (x,y)->check(x.similar(y)){"Record changed: $key\n$x\n$y"} }
            }
            println("PASS: iOS → Android restore preserves every record, amount, link and attachment byte")
        }
        db.close()
    } finally { sandbox.deleteRecursively() }
}
