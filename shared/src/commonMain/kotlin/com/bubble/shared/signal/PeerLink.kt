package com.bubble.shared.signal

import com.bubble.shared.crypto.PairingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** État du tunnel P2P (agrégat PeerConnection + DataChannel). */
enum class PeerLinkState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, CLOSED }

/**
 * Protocole du DataChannel "bubble-meta" — métadonnées légères uniquement.
 * Le canal est chiffré par construction (DTLS, obligatoire en WebRTC) ; aucune
 * image n'y transite jamais (les snapshots ont leur canal binaire dédié).
 */
@Serializable
sealed interface PeerMessage {
    /** Poignée de main : échange des offsets UTC pour la négociation de purge 23h30. */
    @Serializable
    @SerialName("hello")
    data class Hello(val utcOffsetMinutes: Int, val protocolVersion: Int = 1) : PeerMessage

    /** Trigger ML (bisou/cœur), présence (Aura), références snapshot/canvas. */
    @Serializable
    @SerialName("signal")
    data class Signal(val payload: SignalPayload) : PeerMessage
}

object PeerWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(message: PeerMessage): String = json.encodeToString(PeerMessage.serializer(), message)

    fun decode(raw: String): PeerMessage = json.decodeFromString(PeerMessage.serializer(), raw)
}

/**
 * Tunnel P2P abstrait. Implémentations : AndroidPeerLink (WebRTC via stream-webrtc-android),
 * IosPeerLink (GoogleWebRTC, à venir). La négociation SDP/ICE et la reconnexion vivent
 * DANS l'implémentation ; le commun ne voit que des états et des messages.
 *
 * `trySend*` est non-suspendant et honnête : false = non remis (canal fermé/saturé),
 * à l'appelant de mettre en file — c'est le contrat sur lequel repose l'outbox.
 */
interface PeerLink {
    val state: StateFlow<PeerLinkState>

    val incoming: Flow<PeerMessage>

    /** Snapshots floutés chiffrés (canal binaire "bubble-snapshots", non fiable/non ordonné). */
    val incomingSnapshots: Flow<ByteArray>

    fun trySend(message: PeerMessage): Boolean

    fun trySendSnapshot(bytes: ByteArray): Boolean

    suspend fun connect(session: PairingSession)

    suspend fun disconnect()
}
