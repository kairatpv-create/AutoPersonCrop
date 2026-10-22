package kz.kairat.organizer

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Real Android APIs, confined to generated test directories. No real account or production network. */
internal class SyntheticContext(base:Context,private val root:File):ContextWrapper(base){
    override fun getApplicationContext():Context=this
    override fun getFilesDir()=File(root,"files").apply{mkdirs()}
    override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
    override fun getNoBackupFilesDir()=File(root,"no_backup").apply{mkdirs()}
    override fun getDatabasePath(name:String)=File(root,"databases/$name").apply{parentFile!!.mkdirs()}
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?):SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,errorHandler)
    override fun getSharedPreferences(name:String,mode:Int)=baseContext.getSharedPreferences("synthetic-${root.name}-$name",mode)
}
private fun syntheticOnly(){check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1"){"Only run on a disposable synthetic emulator"}}
private fun root(name:String)=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"safety-$name").apply{mkdirs()}
private fun context(directory:File)=SyntheticContext(InstrumentationRegistry.getInstrumentation().targetContext,directory).also{AttachmentStore.initialize(it)}
private fun files(dir:File)=dir.walkTopDown().filter{it.isFile}.associate{it.relativeTo(dir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString(""){b->"%02x".format(b)}}
private fun data(db:NoteDatabase,s:SettingsStore)=JSONObject(BackupManager.createBackup(db,s)).apply{remove("createdAt")}.toString()
private fun session(s:SettingsStore){val email="synthetic@example.invalid";val claims=JSONObject().put("sub","1").put("email",email);s.syncEmail=email;s.syncIdToken="header."+Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray())+".synthetic"}

@RunWith(AndroidJUnit4::class)
class MobileSafetyTest {
    private lateinit var c:SyntheticContext
    private lateinit var db:NoteDatabase
    private lateinit var settings:SettingsStore
    @Before fun setup(){syntheticOnly();c=context(root(UUID.randomUUID().toString()));db=NoteDatabase(c);settings=SettingsStore(c);settings.language="en";settings.setPin("2580");db.save(null,"Synthetic note","private body");db.addMoneyEntry("expense",19.95,"test",null,"synthetic finance");val f=File(c.cacheDir,"attachment.bin").apply{writeBytes(byteArrayOf(1,0,-1,8))};AttachmentStore.addLocalFile("note",1,f)}
    @After fun close(){CloudSync.testTransport=null;db.close()}
    private fun refused(payload:String){val old=data(db,settings);val bytes=files(c.filesDir);assertTrue(runCatching{BackupManager.restore(payload,db,"en")}.isFailure);assertEquals(old,data(db,settings));assertEquals(bytes,files(c.filesDir));assertTrue(settings.verifyPin("2580"))}
    @Test fun incompleteJsonPreservesDatabaseAndAttachments(){refused("{\"notes\":[]}")}
    @Test fun invalidFormatPreservesDatabaseAndAttachments(){refused(JSONObject(BackupManager.createBackup(db,settings)).put("format","wrong").toString())}
    @Test fun invalidTypesPreserveDatabaseAndAttachments(){for(section in SyncSnapshot.sections)refused(JSONObject(BackupManager.createBackup(db,settings)).put(section,"wrong").toString())}
    @Test fun invalidRecordTypeIsRejected(){val r=JSONObject(BackupManager.createBackup(db,settings));r.getJSONArray("money").getJSONObject(0).put("amount","19.95");refused(r.toString())}
    @Test fun validJsonRestoresExactDataAndFileBytes(){val expected=BackupManager.createBackup(db,settings);val bytes=files(c.filesDir);db.save(null,"temporary","remove on explicit restore");BackupManager.restore(expected,db,"en");assertEquals(JSONObject(expected).apply{remove("createdAt")}.toString(),data(db,settings));assertEquals(bytes.filterKeys{!it.startsWith("attachments/.organizer-restore-generation")},files(c.filesDir).filterKeys{!it.startsWith("attachments/.organizer-restore-generation")});val snapshots=File(c.noBackupFilesDir,RestoreSafety.DIRECTORY).listFiles()!!.filter{it.name.startsWith("before-restore-")};assertEquals(1,snapshots.size);RestoreSafety.verify(snapshots.single())}
    @Test fun validZipIsReadAndRestored(){val expected=BackupManager.createBackup(db,settings);val archive=File(c.cacheDir,"synthetic.zip");java.util.zip.ZipOutputStream(archive.outputStream()).use{it.putNextEntry(java.util.zip.ZipEntry("backup.json"));it.write(expected.toByteArray());it.closeEntry()};val read=BackupImportReader.read(archive.inputStream(),c.cacheDir,"en");BackupManager.restore(read,db,"en");assertEquals(JSONObject(expected).apply{remove("createdAt")}.toString(),data(db,settings))}
    @Test fun validEmptyCopyIsDistinctFromIncompleteCopy(){val expected=JSONObject(BackupManager.createBackup(db,settings));SyncSnapshot.sections.forEach{expected.put(it,JSONArray())};expected.put("attachments",JSONArray());BackupManager.restore(expected.toString(),db,"en");assertTrue(db.getNotes().isEmpty());assertTrue(db.getMoneyEntries().isEmpty());assertEquals(0,AttachmentStore.exportBackup().length());assertTrue(settings.verifyPin("2580"))}
    @Test fun reopeningUsesTheCommittedDatabaseAndFiles(){val expected=data(db,settings);db.close();db=NoteDatabase(c);AttachmentStore.initialize(c);assertEquals(expected,data(db,settings))}
    @Test fun cloudVersionSevenPreservesPreferencesAndAttachments(){val bytes=files(c.filesDir);val cloud=BackupManager.createCloudBackup(db,settings);BackupManager.restore(cloud,db,"en");assertEquals(bytes,files(c.filesDir));assertTrue(settings.verifyPin("2580"));assertEquals(1,db.getMoneyEntries().size)}
    @Test fun offlineChangesSurviveReopenAndReconnect(){session(settings);var remote:String?=null;var rev=0L;var offline=false
        CloudSync.testTransport={path,method,body,_->
            if(offline)throw java.io.IOException("synthetic offline")
            when{path=="/api/account"->CloudSync.HttpResult(200,JSONObject().put("email",settings.syncEmail))
                method=="GET"->CloudSync.HttpResult(200,JSONObject().put("exists",remote!=null).put("rev",rev).put("payload",remote?:JSONObject.NULL))
                else->{remote=JSONObject(body!!).getString("payload");rev++;CloudSync.HttpResult(200,JSONObject().put("ok",true).put("rev",rev))}}
        }
        CloudSync.syncOnce(db,settings,"en",c);offline=true;db.save(null,"offline note","pending");val expected=data(db,settings);assertTrue(runCatching{CloudSync.syncOnce(db,settings,"en",c)}.isFailure);assertEquals(expected,data(db,settings));db.close();db=NoteDatabase(c);assertEquals(expected,data(db,settings));offline=false;CloudSync.syncOnce(db,settings,"en",c);assertEquals(SyncSnapshot.parse(BackupManager.createCloudBackup(db,settings),"en"),remote)
    }
    @Test fun accountSwitchAfterLogoutDoesNotSendData(){session(settings);CloudSync.testTransport={path,_,_,_->assertEquals("/api/account",path);CloudSync.HttpResult(200,JSONObject().put("email",settings.syncEmail))};val before=data(db,settings);CloudSync.logout(settings);assertTrue(runCatching{CloudSync.auth(settings,"other@example.invalid","synthetic-password",false)}.isFailure);assertEquals(before,data(db,settings))}
    @Test fun pinProtectedNotificationDoesNotExposePrivateContent(){
        settings.setPinProtectionEnabled(true)
        val id=db.addReminder("note",1,System.currentTimeMillis()+60000,true,false)
        val reminder=db.getReminder(id)!!
        ReminderNotifications.show(c,reminder)
        val notification=c.getSystemService(android.app.NotificationManager::class.java).activeNotifications.first{it.id==id.toInt()}.notification
        assertFalse(notification.extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty().contains("Synthetic note"))
        assertFalse(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("private body"))
        assertNull(notification.fullScreenIntent)
        ReminderNotifications.cancel(c,id)
    }
    @Test fun realSqliteRollbackPreservesRowsAfterRejectedReplacement(){val before=data(db,settings);val bytes=files(c.filesDir);db.writableDatabase.execSQL("CREATE TEMP TRIGGER synthetic_abort BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");assertTrue(runCatching{BackupManager.restore(BackupManager.createBackup(db,settings),db,"en")}.isFailure);db.writableDatabase.execSQL("DROP TRIGGER synthetic_abort");assertEquals(before,data(db,settings));assertEquals(bytes,files(c.filesDir))}
}

/** Invoked as two distinct instrumentation processes, with adb force-stop between them. */
@RunWith(AndroidJUnit4::class)
class MobileRestartTest{
    @Test fun seed(){syntheticOnly();val dir=root("restart");check(!File(dir,"seed.json").exists());val c=context(dir);val db=NoteDatabase(c);val s=SettingsStore(c);s.language="en";s.setPin("2580");db.save(null,"before backup","body");db.addMoneyEntry("expense",99.99,"synthetic",null,"keep");AttachmentStore.addLocalFile("note",1,File(c.cacheDir,"payload.bin").apply{writeText("restart file")});val backup=BackupManager.createBackup(db,s);db.save(null,"temporary","body");BackupManager.restore(backup,db,"en");session(s);SyncStateStore(c,"en").write(SyncStateStore.State("organizer-pro:1",s.syncEmail,SyncSnapshot.parse(BackupManager.createCloudBackup(db,s),"en"),1));db.save(null,"pending offline after restore","body");val expected=data(db,s);check(c.getSharedPreferences("organizer_settings",Context.MODE_PRIVATE).edit().commit());db.close();File(dir,"seed.json").writeText(JSONObject().put("pid",Process.myPid()).put("expected",expected).put("files",JSONObject(files(c.filesDir))).toString())}
    @Test fun verifyAfterForceStop(){syntheticOnly();val dir=root("restart");val seed=JSONObject(File(dir,"seed.json").readText());assertNotEquals(seed.getInt("pid"),Process.myPid());val c=context(dir);val db=NoteDatabase(c);val s=SettingsStore(c);assertEquals(seed.getString("expected"),data(db,s));assertEquals(seed.getJSONObject("files").toString(),JSONObject(files(c.filesDir)).toString());assertTrue(s.verifyPin("2580"));assertNotNull(SyncStateStore(c,"en").read()!!.payload);assertEquals(2,db.getNotes().size);db.close()}
}

@RunWith(AndroidJUnit4::class)
class MobileCrashTest {
    @Test fun haltBeforeSqlCommit(){
        syntheticOnly();val dir=root("crash");check(!File(dir,"seed.json").exists());val c=context(dir);val db=NoteDatabase(c);val s=SettingsStore(c);s.language="en"
        db.save(null,"old before crash","keep");db.addMoneyEntry("income",45.67,"synthetic",null,"keep")
        AttachmentStore.addLocalFile("note",1,File(c.cacheDir,"old.bin").apply{writeText("old attachment bytes")})
        val expected=data(db,s);val bytes=files(c.filesDir)
        val incoming=JSONObject(BackupManager.createBackup(db,s));incoming.getJSONArray("notes").getJSONObject(0).put("title","uncommitted replacement")
        incoming.getJSONArray("attachments").getJSONObject(0).put("data",Base64.getEncoder().encodeToString("new attachment bytes".toByteArray())).put("size","new attachment bytes".toByteArray().size)
        java.io.FileOutputStream(File(dir,"seed.json")).use{out->out.write(JSONObject().put("pid",Process.myPid()).put("expected",expected).put("files",JSONObject(bytes)).toString().toByteArray());out.fd.sync()}
        BackupManager.restore(incoming.toString(),db,"en",object:AttachmentRestoreFiles(){
            override fun move(from:File,to:File){
                super.move(from,to)
                if(from.name=="new"&&to.name=="attachments"){
                    java.io.FileOutputStream(File(dir,"killed-before-commit")).use{out->out.write("synthetic crash".toByteArray());out.fd.sync()}
                    Process.killProcess(Process.myPid());Thread.sleep(1000);throw AssertionError("Process termination failed")
                }
            }
        })
        fail("Expected abrupt process termination")
    }
    @Test fun recoverAfterHalt(){
        syntheticOnly();val dir=root("crash");assertTrue(File(dir,"killed-before-commit").isFile);val seed=JSONObject(File(dir,"seed.json").readText());assertNotEquals(seed.getInt("pid"),Process.myPid())
        val c=context(dir);val db=NoteDatabase(c);val s=SettingsStore(c)
        assertEquals(seed.getString("expected"),data(db,s));assertEquals(seed.getJSONObject("files").toString(),JSONObject(files(c.filesDir)).toString());assertFalse(File(c.filesDir,RestoreJournal.JOURNAL).exists());db.close()
    }
}
