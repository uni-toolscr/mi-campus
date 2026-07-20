package cr.micampus.app.data.banner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import cr.micampus.app.core.model.AcademicProgressSnapshot
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AcademicProgressStorage { fun read(): AcademicProgressSnapshot?; fun save(snapshot: AcademicProgressSnapshot); fun clear() }

internal const val ACADEMIC_PROGRESS_STORE_VERSION = 2
internal fun hasSupportedAcademicProgressVersion(data: ByteArray): Boolean =
    data.size > 14 && data[0].toInt() == ACADEMIC_PROGRESS_STORE_VERSION

/** AES-GCM aggregate store in noBackup; no cookie, grade, course, or HTML data is persisted. */
class AcademicProgressStore(context: Context, private val gson: Gson = Gson()) : AcademicProgressStorage {
    private val file = File(context.noBackupFilesDir, "una_academic_progress.bin")
    private val alias = "micampus_una_academic_progress"
    override fun read(): AcademicProgressSnapshot? = runCatching {
        if (!file.exists()) return null
        val data = file.readBytes()
        if (!hasSupportedAcademicProgressVersion(data)) {
            file.delete()
            return null
        }
        val ivLength = data[1].toInt(); require(ivLength in 12..16 && data.size > 2 + ivLength)
        val iv = data.copyOfRange(2, 2 + ivLength)
        val json = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(doFinal(data.copyOfRange(2 + ivLength, data.size)), StandardCharsets.UTF_8)
        }
        gson.fromJson(json, AcademicProgressSnapshot::class.java)
    }.getOrNull()
    override fun save(snapshot: AcademicProgressSnapshot) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv; val encrypted = cipher.doFinal(gson.toJson(snapshot).toByteArray(StandardCharsets.UTF_8))
        val payload = ByteBuffer.allocate(2 + iv.size + encrypted.size).put(ACADEMIC_PROGRESS_STORE_VERSION.toByte()).put(iv.size.toByte()).put(iv).put(encrypted).array()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try { temp.writeBytes(payload); runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }.getOrElse { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) } } finally { if (temp.exists()) temp.delete() }
    }
    override fun clear() { file.delete() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private companion object { const val TRANSFORMATION = "AES/GCM/NoPadding" }
}
