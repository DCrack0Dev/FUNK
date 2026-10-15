package com.alpacatalk.trading.data

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialVaultInvalidatedException : IllegalStateException("Saved credentials can no longer be decrypted after a device-security change. Remove and save them again after authenticating.")

class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("fin_killer_secure_credentials", Context.MODE_PRIVATE)
    private val keyAlias = "FinKillerProCredentialKeyBackground"
    private val legacyKeyAlias = "FinKillerProCredentialKey"

    fun has(broker: String): Boolean = prefs.contains("$broker.payload")

    fun save(broker: String, values: Map<String, String>) {
        val payload = values.entries.joinToString("\n") { "${encode(it.key)}=${encode(it.value)}" }
        val encrypted = try {
            encrypt(payload.toByteArray(StandardCharsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            prefs.edit().remove("$broker.payload").apply()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
            throw CredentialVaultInvalidatedException()
        }
        prefs.edit().putString("$broker.payload", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun read(broker: String): Map<String, String> {
        val encoded = prefs.getString("$broker.payload", null) ?: return emptyMap()
        val clearBytes = try {
            decrypt(Base64.decode(encoded, Base64.NO_WRAP), key())
        } catch (_: GeneralSecurityException) {
            // Migrate credentials previously protected by the foreground-only key after the
            // user has authenticated in the UI. A background service never prompts or retries
            // this legacy key; it simply reports that credentials must be re-saved if migration
            // has not happened yet.
            val legacy = try { decrypt(Base64.decode(encoded, Base64.NO_WRAP), key(legacyKeyAlias, requiresAuthentication = true)) } catch (_: GeneralSecurityException) { null }
            if (legacy != null) {
                val migrated = encrypt(legacy, key())
                prefs.edit().putString("$broker.payload", Base64.encodeToString(migrated, Base64.NO_WRAP)).apply()
                legacy
            } else throw CredentialVaultInvalidatedException()
        }
        /* catch block retained above to keep invalidated credentials fail-closed */
        /*
            prefs.edit().remove("$broker.payload").apply()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
            throw CredentialVaultInvalidatedException()
        */
        val clear = clearBytes.toString(StandardCharsets.UTF_8)
        return clear.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else decode(line.substring(0, split)) to decode(line.substring(split + 1))
        }.toMap()
    }

    fun delete(broker: String) { prefs.edit().remove("$broker.payload").apply() }

    private fun encrypt(clear: ByteArray, secretKey: SecretKey = key()): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        return iv + cipher.doFinal(clear)
    }

    private fun decrypt(value: ByteArray, secretKey: SecretKey): ByteArray {
        require(value.size > 12) { "Saved credential data is invalid." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, value.copyOfRange(0, 12)))
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }

    private fun key(alias: String = keyAlias, requiresAuthentication: Boolean = false): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            val builder = android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            if (requiresAuthentication) builder.setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(30)
            init(builder.build())
        }.generateKey()
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8)
}
