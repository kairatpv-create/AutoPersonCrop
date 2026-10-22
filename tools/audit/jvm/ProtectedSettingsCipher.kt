package kz.kairat.organizer

import android.content.Context
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/** Host adapter only. Actual Android Keystore is checked by instrumentation, never by this class. */
internal open class ProtectedSettingsCipher(context: Context) {
    private val keyFile = File(context.noBackupFilesDir, "synthetic-host-key")
    private fun key(create: Boolean): SecretKeySpec {
        if(!keyFile.exists()) { check(create); keyFile.writeBytes(ByteArray(32).also(java.security.SecureRandom()::nextBytes)) }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }
    open fun encrypt(plain: ByteArray): ByteArray {
        val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key(true))
        return byteArrayOf(1)+c.iv+c.doFinal(plain)
    }
    open fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size>=30&&bytes[0]==1.toByte())
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        return c.doFinal(bytes.copyOfRange(13,bytes.size))
    }
}
