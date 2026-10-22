package kz.kairat.organizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Same package/schema update on a disposable emulator; the production signing key never enters CI. */
@RunWith(AndroidJUnit4::class)
class LegacyUpgradeTest {
    private fun c():android.content.Context {
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        return InstrumentationRegistry.getInstrumentation().targetContext
    }
    @Test fun seedVersion71(){
        val c=c();val db=NoteDatabase(c)
        check(db.getNotes().isEmpty())
        val n=db.save(null,"Synthetic upgrade note","Keep notes and files")
        val p=db.saveProject(null,"Synthetic upgrade project","","planned",0,0,0,987.65,"","","")
        db.addMoneyEntry("expense",19.95,"synthetic",p,"keep cents")
        AttachmentStore.initialize(c)
        AttachmentStore.addLocalFile("note",n,File(c.cacheDir,"upgrade.bin").apply{writeText("synthetic upgrade file")})
        val salt="synthetic-upgrade-salt"
        val hash=MessageDigest.getInstance("SHA-256").digest((salt+":2580").toByteArray()).joinToString(""){"%02x".format(it)}
        assertTrue(c.getSharedPreferences("organizer_settings",0).edit().putString("pin_salt",salt).putString("pin_hash",hash)
            .putBoolean("pin_enabled",true).putString("sync_id_token","synthetic-upgrade-token")
            .putString("sync_email","upgrade@example.invalid").putString("language","en").putBoolean("dark_theme",true).commit())
        assertTrue(c.getSharedPreferences("organizer_sync_owner",0).edit().putString("owner","organizer-pro:9999").putString("email","upgrade@example.invalid").commit())
        db.close()
    }
    private fun records(){
        val c=c();AttachmentStore.initialize(c);val db=NoteDatabase(c)
        assertEquals("Synthetic upgrade note",db.getNotes().single().title)
        assertEquals(987.65,db.getProjects().single().budget,0.0);assertEquals(19.95,db.getMoneyEntries().single().amount,0.0)
        val attachment=AttachmentStore.exportBackup().getJSONObject(0)
        assertEquals("synthetic upgrade file",String(java.util.Base64.getDecoder().decode(attachment.getString("data"))))
        assertEquals("organizer-pro:9999",c.getSharedPreferences("organizer_sync_owner",0).getString("owner",null));db.close()
    }
    @Test fun verifyVersion72(){
        records();val c=c();val s=SettingsStore(c);s.ensureSecureStorage()
        assertEquals("synthetic-upgrade-token",s.syncIdToken);assertTrue(s.verifyPin("2580"));assertTrue(s.isPinProtectionEnabled())
        assertEquals("en",s.language);assertTrue(s.darkTheme)
        assertTrue(c.getSharedPreferences("organizer_settings",0).all.keys.none{it in ProtectedSettings.keys})
        // The UI smoke test must never attempt production sync, even with this invalid synthetic token.
        s.clearSyncSession();s.setPinProtectionEnabled(false)
    }
    @Test fun verifyReleaseUpdate(){records();assertTrue(SettingsStore(c()).verifyPin("2580"));assertEquals("",SettingsStore(c()).syncIdToken)}
}
