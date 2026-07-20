package com.bubble.shared.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Orchestration de la cérémonie de pairage 1:1 par QR + échange via signalisation.
 *
 * Flux (moins de 10 s, zéro friction) :
 *  1. Émetteur A : [initiator] → génère bi-clé + coupleId, publie [invite] en QR.
 *  2. Récepteur B : [responder] → scanne le QR (a donc déjà pubA), génère sa bi-clé,
 *     calcule immédiatement le secret partagé. B envoie SA pubB à A via le serveur
 *     (SignalingMessage.PairingKey), room = coupleId.
 *  3. A reçoit pubB → [complete] → même secret partagé, même clé de session.
 *
 * Le QR porte la moitié de l'ECDH visuellement (non-MITMable) ; seul B→A passe par le
 * serveur, qui ne voit que des clés publiques. Le safety number certifie le tout.
 *
 * Verrou 1:1 : le serveur (rooms strictes de 2) rejette tout tiers ; le moteur refuse un
 * second lien tant qu'il n'y a pas eu désaffiliation.
 */
class PairingHandshake private constructor(
    private val role: Role,
    private val keyPair: KeyPair,
    private val coupleId: String,
    private val peerPublicKey: ByteArray?,
) {
    private enum class Role { INITIATOR, RESPONDER }

    /** Clé publique locale à transmettre au pair (QR pour A, signaling pour B). */
    val publicKey: ByteArray get() = keyPair.publicKey

    /** Clé publique en Base64 URL — format du SignalingMessage.PairingKey (évite ByteArray côté Swift). */
    @OptIn(ExperimentalEncodingApi::class)
    val publicKeyEncoded: String get() = Base64.UrlSafe.encode(keyPair.publicKey)

    /** Invitation à encoder en QR (émetteur uniquement). */
    val invite: PairingInvite get() = PairingInvite(coupleId, keyPair.publicKey)

    companion object {
        /** Émetteur : nouvelle bi-clé + nouveau coupleId. */
        fun initiator(): PairingHandshake {
            return PairingHandshake(Role.INITIATOR, PairingCrypto.generateKeyPair(), PairingCrypto.newCoupleId(), null)
        }

        /** Récepteur : à partir de l'invite scannée. Retourne null si le QR est invalide. */
        fun responder(inviteRaw: String): Pair<PairingHandshake, PairingResult>? {
            val invite = PairingInvite.decode(inviteRaw) ?: return null
            val handshake = PairingHandshake(
                Role.RESPONDER, PairingCrypto.generateKeyPair(), invite.coupleId, invite.publicKey,
            )
            return handshake to handshake.derive(invite.publicKey)
        }
    }

    /** Émetteur : finalise avec la clé publique du récepteur reçue par signaling. */
    fun complete(peerPublicKey: ByteArray): PairingResult {
        require(role == Role.INITIATOR) { "complete() réservé à l'émetteur" }
        return derive(peerPublicKey)
    }

    /** Variante String (Base64 URL) — reçue via SignalingMessage.PairingKey. Null si invalide. */
    @OptIn(ExperimentalEncodingApi::class)
    fun complete(peerPublicKeyEncoded: String): PairingResult? {
        val pub = runCatching { Base64.UrlSafe.decode(peerPublicKeyEncoded) }.getOrNull() ?: return null
        return complete(pub)
    }

    private fun derive(peerPub: ByteArray): PairingResult {
        val shared = PairingCrypto.sharedSecret(keyPair.privateKey, peerPub)
        val sessionKey = PairingCrypto.deriveSessionKey(shared, coupleId)
        val session = PairingSession(
            coupleId = coupleId,
            selfFingerprint = PairingCrypto.fingerprint(keyPair.publicKey),
            partnerFingerprint = PairingCrypto.fingerprint(peerPub),
            method = PairingMethod.QR_CODE,
        )
        return PairingResult(
            session = session,
            sessionKey = sessionKey,
            safetyNumber = PairingCrypto.safetyNumber(keyPair.publicKey, peerPub),
        )
    }
}
