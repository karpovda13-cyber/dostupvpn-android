package com.dostupvpn.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dostupvpn.app.net.Endpoint
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище: секрет устройства шифруется AES-256-GCM, ключ лежит в Android Keystore
 * (не извлекается из приложения). Список адресов API — не секрет, хранится как есть.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("dostupvpn", Context.MODE_PRIVATE)

    fun deviceToken(): String? = prefs.getString("token", null)?.let { SecretBox.decrypt(it) }

    fun saveLogin(token: String, deviceName: String) {
        prefs.edit()
            .putString("token", SecretBox.encrypt(token))
            .putString("device_name", deviceName)
            .apply()
    }

    fun endpoints(): List<Endpoint> {
        val raw = prefs.getString("endpoints", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Endpoint(o.getString("url"), o.getString("tls"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setEndpoints(list: List<Endpoint>) {
        if (list.isEmpty()) return
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("url", it.url).put("tls", it.tls)) }
        prefs.edit().putString("endpoints", arr.toString()).apply()
    }

    /** Выход: стираем секрет устройства; список адресов оставляем. */
    fun clear() {
        prefs.edit().remove("token").remove("device_name").apply()
    }
}

private object SecretBox {
    private const val ALIAS = "dostupvpn_device_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /** null — если данные повреждены или ключ утрачен (тогда считаем, что пользователь не вошёл). */
    fun decrypt(encoded: String): String? = try {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val body = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
