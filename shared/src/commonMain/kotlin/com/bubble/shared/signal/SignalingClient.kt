package com.bubble.shared.signal

import com.bubble.shared.crypto.PairingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Messages de signalisation WebRTC — STRICTEMENT des métadonnées de connexion.
 *
 * Security-by-design : cette hiérarchie ne peut représenter aucun contenu utilisateur
 * (pas d'image, pas de texte libre, pas de clé privée). Le média circule ensuite en P2P
 * chiffré (DTLS-SRTP) ; le serveur de signalisation ne voit jamais un octet de contenu.
 */
@Serializable
sealed interface SignalingMessage {
    @Serializable
    @SerialName("offer")
    data class SdpOffer(val sdp: String) : SignalingMessage

    @Serializable
    @SerialName("answer")
    data class SdpAnswer(val sdp: String) : SignalingMessage

    @Serializable
    @SerialName("ice")
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
    ) : SignalingMessage

    /**
     * Échange de clé publique du pairage (récepteur → émetteur, room = coupleId).
     * Contenu strictement public (clé X25519 en Base64) : le serveur ne peut rien en dériver.
     */
    @Serializable
    @SerialName("pairing_key")
    data class PairingKey(val publicKey: String) : SignalingMessage

    /** Fin de session volontaire. Pas un statut "Vu" : uniquement du cycle de vie de connexion. */
    @Serializable
    @SerialName("bye")
    data object Bye : SignalingMessage
}

enum class SignalingState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Mise en relation des deux appareils du couple (serveur minimaliste Node/FastAPI plus tard).
 * L'authentification s'appuie sur la [PairingSession] : le serveur route par coupleId
 * sans pouvoir lire autre chose que ces métadonnées.
 */
interface SignalingClient {
    val state: StateFlow<SignalingState>

    /** Messages du partenaire, déjà authentifiés (signature vérifiée contre son fingerprint). */
    val inbound: Flow<SignalingMessage>

    suspend fun connect(session: PairingSession)

    suspend fun send(message: SignalingMessage)

    suspend fun disconnect()
}

/** Configuration ICE — types propres pour ne pas dépendre des libs WebRTC natives ici. */
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

data class RtcConfiguration(val iceServers: List<IceServer>) {
    companion object {
        /** Défaut de développement : STUN public, pas de TURN (ajouté avec le vrai backend). */
        val DEFAULT = RtcConfiguration(
            iceServers = listOf(IceServer(urls = listOf("stun:stun.l.google.com:19302"))),
        )
    }
}
