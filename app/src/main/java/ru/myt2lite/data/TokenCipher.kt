package ru.myt2lite.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование токенов перед записью на диск.
 *
 * Ключ AES-256 создаётся и хранится в Android Keystore (защищённое хранилище телефона): его нельзя
 * прочитать из файлов приложения, он не попадает в резервные копии и не переносится на другой телефон.
 * Шифр AES/GCM: вместе с данными проверяется их целостность; [aad] привязывает запись к её имени
 * (access_token нельзя подменить refresh_token и наоборот).
 *
 * Формат строки: "v1:" + base64(IV(12 байт) + шифртекст с меткой GCM).
 * Обе функции никогда не бросают исключений: при любой ошибке возвращают null.
 */
internal object TokenCipher {

    private const val TAG = "T2Auth"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "t2lite_tokens_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val lock = Any()

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE)
        ks.load(null)
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    private fun keyForEncrypt(): SecretKey = synchronized(lock) {
        existingKey() ?: run {
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(spec)
            generator.generateKey()
        }
    }

    /** Зашифровать. null — хранилище ключей недоступно (тогда вызывающий решает, что делать). */
    fun encrypt(plain: String, aad: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypt()) // IV выбирает сам Keystore (случайный)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) {
            null
        } else {
            PREFIX + Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        Log.w(TAG, "шифрование токена не удалось: ${e.javaClass.simpleName}")
        null
    }

    /** Расшифровать. null — запись повреждена, чужая или ключа больше нет. */
    fun decrypt(stored: String, aad: String): String? = try {
        val key = if (stored.startsWith(PREFIX)) existingKey() else null
        if (key == null) {
            null
        } else {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
                cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
                String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "расшифровка токена не удалась: ${e.javaClass.simpleName}")
        null
    }
}
