package com.bubble.shared.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Range l'identité pairée (session + clé E2EE) chiffrée au repos par une clé AES-256-GCM
 * non exportable de l'Android Keystore (chiffrement enveloppe). La clé de session E2EE ne
 * touche jamais le disque en clair ; elle n'est déchiffrée en mémoire qu'au démarrage.
 */
class AndroidPairedIdentityStore(context: Context) : PairedIdentityStore {

    private val file = File(context.filesDir, "paired_identity.enc")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Persisted(val session: PairingSession, val sessionKeyB64: String)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun save(identity: PairedIdentity) = withContext(Dispatchers.IO) {
        val plain = json.encodeToString(
            Persisted.serializer(),
            Persisted(identity.session, Base64.encode(identity.sessionKey)),
        ).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val body = cipher.doFinal(plain)
        file.writeBytes(cipher.iv + body) // IV(12) || ciphertext
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun load(): PairedIdentity? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val all = file.readBytes()
            val iv = all.copyOf(GCM_IV_LENGTH)
            val body = all.copyOfRange(GCM_IV_LENGTH, all.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            val plain = cipher.doFinal(body).decodeToString()
            val persisted = json.decodeFromString(Persisted.serializer(), plain)
            PairedIdentity(persisted.session, Base64.decode(persisted.sessionKeyB64))
        }.getOrNull()
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bubble_identity_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
