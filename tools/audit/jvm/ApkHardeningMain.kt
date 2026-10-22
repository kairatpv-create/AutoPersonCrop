package kz.kairat.organizer

import android.content.Context
import java.nio.file.Files
import java.security.MessageDigest

private fun fresh()=Context(Files.createTempDirectory("apk-hardening-synthetic-").toFile())
private fun legacy(c:Context) {
    val salt="synthetic-salt"
    val hash=MessageDigest.getInstance("SHA-256").digest((salt+":2580").toByteArray()).joinToString(""){"%02x".format(it)}
    check(c.getSharedPreferences("organizer_settings",0).edit().putString("pin_salt",salt).putString("pin_hash",hash)
        .putBoolean("pin_enabled",true).putString("sync_id_token","synthetic-bearer-secret").putString("sync_refresh_token","synthetic-refresh-secret")
        .putString("sync_uid","synthetic-uid").putLong("sync_token_expiry",9876543210L)
        .putString("sync_email","synthetic@example.invalid").putBoolean("dark_theme",true).putString("language","en").commit())
}
fun main(){
    d06Test("Failed interface preference commit retries migration without losing legacy credentials"){
        val c=fresh();legacy(c);val old=c.getSharedPreferences("organizer_settings",0).all
        System.setProperty("organizer.audit.failPreferencesCommit","true")
        try { check(runCatching{SettingsStore(c)}.isFailure) } finally { System.clearProperty("organizer.audit.failPreferencesCommit") }
        check(c.getSharedPreferences("organizer_settings",0).all==old)
        val s=SettingsStore(c);s.ensureSecureStorage();check(s.darkTheme&&s.language=="en"&&s.verifyPin("2580")&&s.syncIdToken=="synthetic-bearer-secret")
    }
    d06Test("Interrupted legacy cleanup resumes from the already published encrypted credentials"){
        val c=fresh();legacy(c);val s=SettingsStore(c)
        System.setProperty("organizer.audit.failPreferencesCommit","true")
        try { check(runCatching{s.ensureSecureStorage()}.isFailure) } finally { System.clearProperty("organizer.audit.failPreferencesCommit") }
        check(ProtectedSettings(c).file.isFile)
        val reopened=SettingsStore(Context(c.sandbox));reopened.ensureSecureStorage()
        check(reopened.verifyPin("2580")&&reopened.syncIdToken=="synthetic-bearer-secret")
    }
    d06Test("A failed credential update retains the previous authenticated file and session"){
        val c=fresh();legacy(c);val s=SettingsStore(c);s.ensureSecureStorage();val file=ProtectedSettings(c).file;val old=file.readBytes()
        val broken=ProtectedSettings(c,io=object:AttachmentRestoreFiles(){override fun write(file:java.io.File,bytes:ByteArray){throw java.io.IOException("synthetic write failure")}})
        check(runCatching{broken.put("sync_id_token","replacement")}.isFailure);check(old.contentEquals(file.readBytes()))
        check(SettingsStore(c).syncIdToken=="synthetic-bearer-secret"&&SettingsStore(c).verifyPin("2580"))
    }
    d06Test("Keystore facade migration retains legacy PIN, session and interface settings"){
        val c=fresh();legacy(c);val s=SettingsStore(c);s.ensureSecureStorage()
        check(s.isPinProtectionEnabled()&&s.verifyPin("2580")&&!s.verifyPin("0000"))
        check(s.syncIdToken=="synthetic-bearer-secret"&&s.syncRefreshToken=="synthetic-refresh-secret"&&s.syncTokenExpiry==9876543210L)
        check(s.darkTheme&&s.language=="en"&&s.syncEmail=="synthetic@example.invalid")
        check(c.getSharedPreferences("organizer_settings",0).all.keys.none{it in ProtectedSettings.keys})
        check(c.getSharedPreferences("organizer_preferences",0).all.keys.none{it in ProtectedSettings.keys})
        check(!ProtectedSettings(c).file.readText(Charsets.ISO_8859_1).contains("synthetic-bearer-secret"))
        check(SettingsStore(c).verifyPin("2580"))
    }
    d06Test("Encryption failure leaves legacy credentials and database untouched"){
        val c=fresh();legacy(c);val db=NoteDatabase(c);db.save(null,"keep","synthetic")
        val before=c.getSharedPreferences("organizer_settings",0).all
        val broken=ProtectedSettings(c,object:ProtectedSettingsCipher(c){override fun encrypt(plain:ByteArray):ByteArray=throw java.io.IOException("synthetic key failure")})
        check(runCatching{broken.ensure()}.isFailure);check(before==c.getSharedPreferences("organizer_settings",0).all)
        check(db.getNotes().single().title=="keep"&&!broken.file.exists());db.close()
    }
    d06Test("Disk failure before publication keeps old credentials"){
        val c=fresh();legacy(c);val before=c.getSharedPreferences("organizer_settings",0).all
        val broken=ProtectedSettings(c,io=object:AttachmentRestoreFiles(){override fun write(file:java.io.File,bytes:ByteArray){throw java.io.IOException("synthetic full disk")}})
        check(runCatching{broken.ensure()}.isFailure);check(before==c.getSharedPreferences("organizer_settings",0).all)
        check(SettingsStore(c).verifyPin("2580"))
    }
    d06Test("Corrupt encrypted settings fail closed without overwriting their bytes"){
        val c=fresh();legacy(c);val s=SettingsStore(c);s.ensureSecureStorage();val file=ProtectedSettings(c).file
        val broken=file.readBytes().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()};file.writeBytes(broken)
        check(runCatching{s.ensureSecureStorage()}.isFailure);check(s.isPinProtectionEnabled()&&!s.verifyPin("2580"))
        check(runCatching{s.syncIdToken="replacement"}.isFailure);check(file.readBytes().contentEquals(broken))
    }
    d06Test("Logout cannot resurrect credentials from the legacy file"){
        val c=fresh();legacy(c);val s=SettingsStore(c);s.ensureSecureStorage();s.clearSyncSession()
        c.getSharedPreferences("organizer_settings",0).edit().putString("sync_id_token","stale").commit()
        check(SettingsStore(c).syncIdToken.isEmpty());check(s.verifyPin("2580"))
    }
    for(value in listOf(0.0,0.01,19.95,987.65,1234567.89,12.3456789,1.0E20))d06Test("Fractional budget survives edit round trip: $value"){
        check(MoneyAmounts.parse(MoneyAmounts.edit(value))==value)
        val c=fresh();val db=NoteDatabase(c);val id=db.saveProject(null,"test","","planned",0,0,0,value,"","","")
        val p=db.getProject(id)!!;db.saveProject(id,"renamed",p.description,p.status,p.progress,p.startAt,p.dueAt,MoneyAmounts.parse(MoneyAmounts.edit(p.budget))!!,p.client,p.address,p.contact)
        check(db.getProject(id)!!.budget==value);db.close()
    }
    for(raw in listOf("NaN","Infinity","1e309","-100","1,2,3","1.2.3","12x34",""))d06Test("Reject invalid monetary input without changing meaning: $raw"){check(MoneyAmounts.parse(MoneyAmounts.formatInput(raw))==null)}
    for(amount in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1.0,0.0))d06Test("Reject invalid financial write: $amount"){
        val c=fresh();val db=NoteDatabase(c);check(runCatching{db.addMoneyEntry("expense",amount,"",null,"")}.isFailure);check(db.getMoneyEntries().isEmpty());db.close()
    }
    d06Test("Reject unknown financial type and missing project without creating rows"){
        val db=NoteDatabase(fresh());check(runCatching{db.addMoneyEntry("wrong",1.0,"",null,"")}.isFailure)
        check(runCatching{db.addMoneyEntry("income",1.0,"",999,"")}.isFailure);check(db.getMoneyEntries().isEmpty());db.close()
    }
    d06Finish("apk-hardening-host-crypto-adapter")
}
