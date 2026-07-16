package cr.micampus.app.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ApiKeyProvider { fun read(): String? }
class ApiKeyStore(private val context: Context) : ApiKeyProvider {
    private val file get() = File(context.noBackupFilesDir, "gemini_key.bin")
    private val alias = "micampus_gemini_key"
    private fun key(): SecretKey { val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; (ks.getKey(alias, null) as? SecretKey)?.let { return it }; return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey() }
    fun save(value: String) { require(value.isNotBlank()); val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }; val iv = cipher.iv; val cipherText = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)); val payload = ByteBuffer.allocate(1 + 1 + iv.size + cipherText.size).put(1).put(iv.size.toByte()).put(iv).put(cipherText).array(); val temp = File(file.parentFile, "${file.name}.tmp"); try { temp.writeBytes(payload); runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }.getOrElse { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) } } finally { if (temp.exists()) temp.delete() } }
    override fun read(): String? = runCatching { val data = file.readBytes(); require(data.size > 14 && data[0].toInt() == 1); val ivLength = data[1].toInt(); require(ivLength in 12..16 && data.size > 2 + ivLength); val iv = data.copyOfRange(2, 2 + ivLength); val cipherText = data.copyOfRange(2 + ivLength, data.size); Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); String(doFinal(cipherText), StandardCharsets.UTF_8) } }.getOrNull()
    fun clear() { file.delete() }
}
