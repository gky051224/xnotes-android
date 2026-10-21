package com.xnotes.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Keys are encrypted with Android Keystore; settings live outside Android backup. */
class AiPreferences(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ai-provider.json"))

    fun load(): AiConfig {
        if (!file.baseFile.exists()) return AiConfig()
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        val encrypted = json.optString("key")
        val apiKey = if (encrypted.isEmpty()) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
        return AiConfig(json.optString("baseUrl"), json.optString("model"), apiKey, json.optBoolean("vision", true))
    }

    fun save(config: AiConfig) {
        if (config.apiKey.isBlank() && config.baseUrl.isBlank() && config.model.isBlank()) {
            file.delete()
            return
        }
        config.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("baseUrl", config.baseUrl.trim()).put("model", config.model.trim())
            .put("vision", config.vision).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("key", Base64.encodeToString(cipher.doFinal(config.apiKey.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "xnotes.ai.provider.v1" }
}
