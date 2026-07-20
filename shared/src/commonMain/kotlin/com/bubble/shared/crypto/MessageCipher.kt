package com.bubble.shared.crypto

/**
 * Chiffrement E2EE applicatif des payloads P2P, au-dessus de la clé de session dérivée au
 * pairage. Vient EN PLUS de DTLS (transport WebRTC) : même un relais TURN ou un serveur
 * compromis ne voit que des octets scellés.
 *
 * [seal] retourne `nonce(12) || ciphertext || tag(16)` ; [open] renvoie null si le tag est
 * invalide (message altéré ou mauvaise clé). Sans cipher (mode dev/loopback), on passe en clair.
 */
interface MessageCipher {
    fun seal(plaintext: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray?
}

/**
 * ChaCha20-Poly1305 avec nonce aléatoire 96 bits par message. À notre volume de messages
 * (gestes, présence, snapshots basse fréquence) et avec rotation quotidienne de clé, le
 * risque de collision de nonce est négligeable, et l'implémentation reste sans état.
 */
class AeadMessageCipher(private val sessionKey: ByteArray) : MessageCipher {
    init {
        require(sessionKey.size == ChaCha20Poly1305.KEY_SIZE) { "clé de session invalide" }
    }

    override fun seal(plaintext: ByteArray): ByteArray {
        val nonce = secureRandomBytes(ChaCha20Poly1305.NONCE_SIZE)
        return nonce + ChaCha20Poly1305.seal(sessionKey, nonce, plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size < ChaCha20Poly1305.NONCE_SIZE + ChaCha20Poly1305.TAG_SIZE) return null
        val nonce = sealed.copyOf(ChaCha20Poly1305.NONCE_SIZE)
        val body = sealed.copyOfRange(ChaCha20Poly1305.NONCE_SIZE, sealed.size)
        return ChaCha20Poly1305.open(sessionKey, nonce, body)
    }
}
