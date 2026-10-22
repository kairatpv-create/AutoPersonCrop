package kz.kairat.organizer

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ApkHardeningTest {
    private fun fresh(): SyntheticContext {
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        return SyntheticContext(c,File(c.cacheDir,"apk-${UUID.randomUUID()}").apply{mkdirs()})
    }
    private fun legacy(c:Context){
        val salt="synthetic-salt"
        val hash=MessageDigest.getInstance("SHA-256").digest((salt+":2580").toByteArray()).joinToString(""){"%02x".format(it)}
        assertTrue(c.getSharedPreferences("organizer_settings",0).edit().putString("pin_hash",hash).putString("pin_salt",salt)
            .putBoolean("pin_enabled",true).putString("sync_id_token","synthetic-bearer-secret")
            .putString("sync_refresh_token","synthetic-refresh-secret").putString("sync_email","test@example.invalid")
            .putBoolean("dark_theme",true).putString("language","en").commit())
    }
    @Test fun realKeystoreMigrationPreservesPinSessionAndPreferences(){
        val c=fresh();legacy(c);val s=SettingsStore(c);s.ensureSecureStorage()
        assertTrue(s.isPinProtectionEnabled());assertTrue(s.verifyPin("2580"));assertFalse(s.verifyPin("0000"))
        assertEquals("synthetic-bearer-secret",s.syncIdToken);assertEquals("synthetic-refresh-secret",s.syncRefreshToken)
        assertTrue(s.darkTheme);assertEquals("en",s.language)
        assertTrue(c.getSharedPreferences("organizer_settings",0).all.keys.none{it in ProtectedSettings.keys})
        assertTrue(c.getSharedPreferences("organizer_preferences",0).all.keys.none{it in ProtectedSettings.keys})
        assertFalse(ProtectedSettings(c).file.readText(Charsets.ISO_8859_1).contains("synthetic-bearer-secret"))
        assertTrue(SettingsStore(c).verifyPin("2580"))
    }
    @Test fun failedEncryptionPreservesLegacyCredentialsAndData(){
        val c=fresh();legacy(c);val db=NoteDatabase(c);db.save(null,"keep","synthetic");val before=c.getSharedPreferences("organizer_settings",0).all
        val broken=ProtectedSettings(c,object:ProtectedSettingsCipher(c){override fun encrypt(plain:ByteArray):ByteArray=throw java.io.IOException("synthetic unavailable key")})
        assertTrue(runCatching{broken.ensure()}.isFailure);assertEquals(before,c.getSharedPreferences("organizer_settings",0).all)
        assertEquals("keep",db.getNotes().single().title);assertFalse(broken.file.exists());assertTrue(SettingsStore(c).verifyPin("2580"));db.close()
    }
    @Test fun failedDiskWritePreservesCredentials(){
        val c=fresh();legacy(c);val before=c.getSharedPreferences("organizer_settings",0).all
        val broken=ProtectedSettings(c,io=object:AttachmentRestoreFiles(){override fun write(file:File,bytes:ByteArray){throw java.io.IOException("synthetic full disk")}})
        assertTrue(runCatching{broken.ensure()}.isFailure);assertEquals(before,c.getSharedPreferences("organizer_settings",0).all)
        assertTrue(SettingsStore(c).verifyPin("2580"))
    }
    @Test fun damagedCiphertextFailsClosedAndRetainsOriginalBytes(){
        val c=fresh();legacy(c);val s=SettingsStore(c);s.ensureSecureStorage();val f=ProtectedSettings(c).file
        val broken=f.readBytes().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()};f.writeBytes(broken)
        assertTrue(runCatching{s.ensureSecureStorage()}.isFailure);assertTrue(s.isPinProtectionEnabled());assertFalse(s.verifyPin("2580"))
        assertTrue(runCatching{s.syncIdToken="replacement"}.isFailure);assertArrayEquals(broken,f.readBytes())
    }
    @Test fun pinAttemptsAreLimitedAcrossStoreRecreation(){
        val c=fresh();val s=SettingsStore(c);s.setPin("2580");s.setPinProtectionEnabled(true)
        repeat(5){assertFalse(SettingsStore(c).verifyPin("0000"))}
        assertTrue(SettingsStore(c).pinRetrySeconds()>0);assertFalse(SettingsStore(c).verifyPin("2580"))
        ProtectedSettings(c).put("pin_locked_until",System.currentTimeMillis()-1)
        assertTrue(SettingsStore(c).verifyPin("2580"));assertEquals(0,SettingsStore(c).pinRetrySeconds())
    }
    @Test fun logoutDoesNotResurrectLegacyCredentials(){
        val c=fresh();legacy(c);val s=SettingsStore(c);s.clearSyncSession()
        c.getSharedPreferences("organizer_settings",0).edit().putString("sync_id_token","stale").commit()
        assertEquals("",SettingsStore(c).syncIdToken);assertTrue(s.verifyPin("2580"))
    }
    @Test fun fractionalBudgetAndFinancialRecordsSurviveEditBackupAndRestore(){
        val c=fresh();AttachmentStore.initialize(c);val db=NoteDatabase(c);val s=SettingsStore(c)
        val id=db.saveProject(null,"before","","planned",0,0,0,987.65,"","","")
        val project=db.getProject(id)!!;val amount=MoneyAmounts.parse(MoneyAmounts.edit(project.budget))!!
        db.saveProject(id,"renamed","","planned",0,0,0,amount,"","","")
        db.addMoneyEntry("expense",19.95,"test",id,"keep cents")
        BackupManager.restore(BackupManager.createBackup(db,s),db,"en")
        assertEquals(987.65,db.getProject(id)!!.budget,0.0);assertEquals(19.95,db.getMoneyEntries().single().amount,0.0)
        for(invalid in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1.0,0.0))assertTrue(runCatching{db.addMoneyEntry("income",invalid,"",null,"")}.isFailure)
        assertTrue(runCatching{db.addMoneyEntry("wrong",1.0,"",null,"")}.isFailure)
        assertTrue(runCatching{db.addMoneyEntry("income",1.0,"",999,"")}.isFailure)
        assertEquals(1,db.getMoneyEntries().size);db.close()
    }
    @Test fun perReminderChannelsRespectSoundAndVibration(){
        val c=fresh();ReminderNotifications.ensureChannel(c)
        val manager=c.getSystemService(android.app.NotificationManager::class.java)
        for(sound in listOf(false,true))for(vibrate in listOf(false,true)){
            val ch=manager.getNotificationChannel("organizer_reminders_v4_${if(sound)1 else 0}_${if(vibrate)1 else 0}")
            assertNotNull(ch);assertEquals(sound,ch.sound!=null);assertEquals(vibrate,ch.shouldVibrate())
        }
    }
}
