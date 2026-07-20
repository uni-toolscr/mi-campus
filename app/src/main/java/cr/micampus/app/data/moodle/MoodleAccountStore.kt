package cr.micampus.app.data.moodle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
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

data class MoodleAccount(
    val token: String,
    val userId: Long,
    val displayName: String,
    val lastSyncEpoch: Long? = null,
)

interface MoodleAccountStorage {
    fun read(): MoodleAccount?
    fun save(account: MoodleAccount)
    fun clear()
}

class MoodleAccountStore(
    context: Context,
    private val gson: Gson = Gson(),
) : MoodleAccountStorage {
    private val file = File(context.noBackupFilesDir, "una_moodle_account.bin")
    private val alias = "micampus_una_moodle_account"

    override fun read(): MoodleAccount? {
        if (!file.exists()) return null
        return runCatching {
            val data = file.readBytes()
            require(data.size > 14 && data[0].toInt() == FORMAT_VERSION)
            val ivLength = data[1].toInt()
            require(ivLength in 12..16 && data.size > 2 + ivLength)
            val iv = data.copyOfRange(2, 2 + ivLength)
            val cipherText = data.copyOfRange(2 + ivLength, data.size)
            val json = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(doFinal(cipherText), StandardCharsets.UTF_8)
            }
            gson.fromJson(json, MoodleAccount::class.java).takeIf { it.token.isNotBlank() }
        }.getOrNull()
    }

    override fun save(account: MoodleAccount) {
        require(account.token.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val cipherText = cipher.doFinal(gson.toJson(account).toByteArray(StandardCharsets.UTF_8))
        val payload = ByteBuffer.allocate(2 + iv.size + cipherText.size)
            .put(FORMAT_VERSION.toByte())
            .put(iv.size.toByte())
            .put(iv)
            .put(cipherText)
            .array()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeBytes(payload)
            runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
