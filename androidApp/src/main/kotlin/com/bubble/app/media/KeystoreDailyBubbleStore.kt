package com.bubble.app.media

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bubble.shared.core.DailyBubbleStatus
import com.bubble.shared.media.SecureDailyBubbleStore
import com.bubble.shared.purge.DailyBubbleStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stockage chiffré du Daily Bubble (AES-256-GCM, clé non exportable dans l'Android Keystore).
 *
 * Le montage n'existe en clair que le temps de sa lecture (fichier temporaire supprimé après).
 * Au repos, seul le fichier `.enc` (IV || ciphertext) subsiste — illisible sans la clé matérielle.
 *
 * Implémente les deux facettes :
 *  - [SecureDailyBubbleStore] pour le DailyBubbleCoordinator (persist/openForPlayback/delete).
 *  - [DailyBubbleStore] pour le PurgeEngine (status/delete) — l'exception "non visionné"
 *    est décidée par le statut fourni ([statusProvider], branché sur le manager).
 */
class KeystoreDailyBubbleStore(
    private val secureDir: File,
    private val statusProvider: () -> DailyBubbleStatus,
) : SecureDailyBubbleStore, DailyBubbleStore {

    private val encFile = File(secureDir, "daily_bubble.enc")

    // ── SecureDailyBubbleStore ───────────────────────────────────────────────────

    override suspend fun persist(plainOutputPath: String): String = withContext(Dispatchers.IO) {
        secureDir.mkdirs()
        val plain = File(plainOutputPath)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        encFile.outputStream().use { out ->
            out.write(cipher.iv) // IV (12 o) en tête, en clair : standard GCM
            plain.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    cipher.update(buffer, 0, read)?.let(out::write)
                }
                cipher.doFinal()?.let(out::write)
            }
        }
        plain.delete() // le clair de travail ne survit jamais à la persistance
        encFile.absolutePath
    }

    override suspend fun openForPlayback(): String? = withContext(Dispatchers.IO) {
        if (!encFile.exists()) return@withContext null
        val temp = File.createTempFile("bubble_play", ".mp4", secureDir)
        encFile.inputStream().use { input ->
            val iv = ByteArray(GCM_IV_LENGTH).also { input.read(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            temp.outputStream().use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    cipher.update(buffer, 0, read)?.let(out::write)
                }
                cipher.doFinal()?.let(out::write)
            }
        }
        temp.absolutePath
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) { encFile.delete() }
    }

    override fun exists(): Boolean = encFile.exists()

    // ── DailyBubbleStore (vu par le PurgeEngine) ─────────────────────────────────
    // status() ci-dessous ; delete() est partagé avec SecureDailyBubbleStore (même signature).

    override fun status(): DailyBubbleStatus = statusProvider()

    // ── Clé Keystore ─────────────────────────────────────────────────────────────

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bubble_daily_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val BUFFER = 64 * 1024
    }
}
