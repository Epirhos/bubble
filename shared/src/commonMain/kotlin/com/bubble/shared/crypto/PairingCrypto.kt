package com.bubble.shared.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Bi-clé X25519 éphémère. La privée ne quitte JAMAIS l'appareil (ni QR, ni réseau). */
class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

/**
 * Contenu du QR d'invitation — uniquement des données publiques, généré à la volée,
 * jamais persisté sur un cloud (contrainte security-by-design). Format compact :
 * `bubble1:<coupleId>:<pubKeyBase64Url>`.
 */
data class PairingInvite(val coupleId: String, val publicKey: ByteArray) {

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(): String = "$PREFIX:$coupleId:${Base64.UrlSafe.encode(publicKey)}"

    companion object {
        private const val PREFIX = "bubble1"

        @OptIn(ExperimentalEncodingApi::class)
        fun decode(raw: String): PairingInvite? {
            val parts = raw.split(":")
            if (parts.size != 3 || parts[0] != PREFIX) return null
            val pub = runCatching { Base64.UrlSafe.decode(parts[2]) }.getOrNull() ?: return null
            if (pub.size != X25519.KEY_SIZE) return null
            return PairingInvite(parts[1], pub)
        }
    }
}

/** Résultat d'un pairage réussi : session publique + clé E2EE dérivée + safety number. */
class PairingResult(
    val session: PairingSession,
    /** Clé symétrique 32 o pour l'E2EE. À ranger dans le Keystore/Keychain, jamais exposée. */
    val sessionKey: ByteArray,
    /** Nombre de sécurité (safety number) à comparer de vive voix pour bloquer un MITM. */
    val safetyNumber: String,
)

/**
 * Primitives de la cérémonie de pairage 1:1 (voir [PairingHandshake] pour l'orchestration).
 * Toute la crypto est on-device : le serveur ne voit que des clés publiques, jamais le secret.
 */
object PairingCrypto {
    private const val KDF_INFO = "bubble-e2ee-session-v1"
    private const val FP_WORDS = 6

    fun generateKeyPair(): KeyPair {
        val priv = secureRandomBytes(X25519.KEY_SIZE)
        return KeyPair(priv, X25519.scalarMultBase(priv))
    }

    /** coupleId opaque = 16 octets aléatoires en Base64 URL (aucune donnée personnelle). */
    @OptIn(ExperimentalEncodingApi::class)
    fun newCoupleId(): String = Base64.UrlSafe.encode(secureRandomBytes(16)).trimEnd('=')

    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray =
        X25519.scalarMult(privateKey, peerPublicKey)

    /**
     * Dérive la clé de session E2EE : HKDF-SHA256 du secret ECDH, salé par le coupleId.
     * Les deux appareils calculent la même valeur sans jamais échanger le secret.
     */
    fun deriveSessionKey(sharedSecret: ByteArray, coupleId: String): ByteArray =
        Hkdf.derive(sharedSecret, coupleId.encodeToByteArray(), KDF_INFO.encodeToByteArray(), 32)

    /**
     * Fingerprint public d'une clé (SHA-256 tronqué en mots hexadécimaux courts).
     * Sert d'identité de vérification dans [PairingSession].
     */
    fun fingerprint(publicKey: ByteArray): String =
        Sha256.hash(publicKey).take(FP_WORDS * 2).chunked(2).joinToString("-") { pair ->
            pair.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

    /**
     * Safety number : empreinte commune des deux clés publiques (ordre canonique), en groupes
     * de chiffres — les partenaires le comparent une fois pour certifier l'absence de MITM.
     */
    fun safetyNumber(pubA: ByteArray, pubB: ByteArray): String {
        val ordered = if (compare(pubA, pubB) <= 0) pubA + pubB else pubB + pubA
        val digest = Sha256.hash(ordered)
        return digest.take(10).joinToString(" ") { (it.toInt() and 0xff).toString().padStart(3, '0') }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }
}
