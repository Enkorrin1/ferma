package com.elevium.mobileposteragent.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.elevium.mobileposteragent.model.AgentConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AgentConfig? {
        loadEncrypted()?.let { return it }
        return loadLegacy()?.also(::save)
    }

    fun save(config: AgentConfig) {
        prefs.edit()
            .putString(KEY_HUB_URL, encrypt(config.hubUrl.trim()))
            .putString(KEY_RUNNER_TOKEN, encrypt(config.runnerToken.trim()))
            .putString(KEY_DEVICE_LABEL, encrypt(config.deviceLabel.trim()))
            .putString(KEY_ACCOUNT_LABEL, config.accountLabel?.trim()?.let(::encrypt))
            .putString(KEY_PINTEREST_BOARD, config.pinterestBoard?.trim()?.takeIf(String::isNotEmpty)?.let(::encrypt))
            .apply()
        legacyPrefs.edit()
            .remove(KEY_HUB_URL)
            .remove(KEY_RUNNER_TOKEN)
            .remove(KEY_DEVICE_LABEL)
            .remove(KEY_ACCOUNT_LABEL)
            .remove(KEY_PINTEREST_BOARD)
            .apply()
    }

    private fun loadEncrypted(): AgentConfig? = runCatching {
        AgentConfig(
            hubUrl = prefs.getString(KEY_HUB_URL, null)?.let(::decrypt) ?: return null,
            runnerToken = prefs.getString(KEY_RUNNER_TOKEN, null)?.let(::decrypt) ?: return null,
            deviceLabel = prefs.getString(KEY_DEVICE_LABEL, null)?.let(::decrypt) ?: return null,
            accountLabel = prefs.getString(KEY_ACCOUNT_LABEL, null)?.let(::decrypt),
            pinterestBoard = prefs.getString(KEY_PINTEREST_BOARD, null)?.let(::decrypt),
        )
    }.getOrNull()

    private fun loadLegacy(): AgentConfig? {
        val hubUrl = legacyPrefs.getString(KEY_HUB_URL, null) ?: return null
        val runnerToken = legacyPrefs.getString(KEY_RUNNER_TOKEN, null) ?: return null
        val deviceLabel = legacyPrefs.getString(KEY_DEVICE_LABEL, null) ?: return null
        return AgentConfig(
            hubUrl,
            runnerToken,
            deviceLabel,
            legacyPrefs.getString(KEY_ACCOUNT_LABEL, null),
            legacyPrefs.getString(KEY_PINTEREST_BOARD, null),
        )
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted configuration value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        private const val SECURE_PREFS_NAME = "mobile_poster_agent_secure"
        private const val LEGACY_PREFS_NAME = "mobile_poster_agent"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mobile_poster_agent_config_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_HUB_URL = "hub_url"
        private const val KEY_RUNNER_TOKEN = "runner_token"
        private const val KEY_DEVICE_LABEL = "device_label"
        private const val KEY_ACCOUNT_LABEL = "account_label"
        private const val KEY_PINTEREST_BOARD = "pinterest_board"
    }
}
