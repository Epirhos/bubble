package com.bubble.app.pairing

import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.crypto.PairingHandshake
import com.bubble.shared.crypto.PairingResult
import com.bubble.shared.signal.SignalingClient
import com.bubble.shared.signal.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Étapes visibles de la cérémonie, pilotant l'UI. */
sealed interface PairingUiState {
    data object Choice : PairingUiState

    /** Émetteur : QR affiché, en attente que le partenaire scanne et réponde. */
    data class ShowingInvite(val qrPayload: String) : PairingUiState

    data object Scanning : PairingUiState

    /** Handshake réussi : safety number à comparer de vive voix avant d'entrer. */
    data class Paired(val safetyNumber: String) : PairingUiState

    data class Error(val message: String) : PairingUiState
}

/**
 * Pilote la cérémonie de pairage côté Android : relie [PairingHandshake] (crypto KMP) au
 * [SignalingClient] (échange de clé publique B→A) et au [BubbleStateManager] (transition
 * vers Ambient). La clé de session E2EE dérivée est remise via [onSessionKey] pour rangement
 * sécurisé (Keystore) — jamais loggée, jamais affichée.
 */
class PairingController(
    private val scope: CoroutineScope,
    private val manager: BubbleStateManager,
    private val signaling: SignalingClient,
    /** Invoqué à la réussite : l'app persiste l'identité chiffrée et monte le lien P2P E2EE. */
    private val onPaired: (PairingResult) -> Unit,
) {
    private val _ui = MutableStateFlow<PairingUiState>(PairingUiState.Choice)
    val ui: StateFlow<PairingUiState> = _ui.asStateFlow()

    private var handshake: PairingHandshake? = null

    /** Émetteur : génère l'invitation, ouvre la room, attend la clé publique du récepteur. */
    fun generateInvite() {
        val hs = PairingHandshake.initiator()
        handshake = hs
        _ui.value = PairingUiState.ShowingInvite(hs.invite.encode())
        scope.launch {
            signaling.connect(sessionForRoom(hs.invite.coupleId, hs.invite.publicKey))
            signaling.inbound.collect { message ->
                if (message is SignalingMessage.PairingKey) {
                    val peerPub = decodeKey(message.publicKey) ?: return@collect
                    finish(hs.complete(peerPub))
                }
            }
        }
    }

    fun startScanning() {
        _ui.value = PairingUiState.Scanning
    }

    /** Récepteur : QR scanné → dérive la session, renvoie SA clé publique à l'émetteur. */
    fun onQrScanned(raw: String) {
        if (_ui.value !is PairingUiState.Scanning) return
        val result = PairingHandshake.responder(raw)
        if (result == null) {
            _ui.value = PairingUiState.Error("Invitation invalide")
            return
        }
        val (hs, pairingResult) = result
        handshake = hs
        scope.launch {
            signaling.connect(sessionForRoom(pairingResult.session.coupleId, hs.publicKey))
            signaling.send(SignalingMessage.PairingKey(encodeKey(hs.publicKey)))
            finish(pairingResult)
        }
    }

    private fun finish(result: PairingResult) {
        if (manager.onPaired(result.session)) {
            onPaired(result) // l'app persiste + monte le lien E2EE (réutilise ce signaling)
            _ui.value = PairingUiState.Paired(result.safetyNumber)
        } else {
            _ui.value = PairingUiState.Error("Déjà lié à un partenaire — désaffilier d'abord")
        }
    }

    private fun encodeKey(key: ByteArray): String =
        key.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun decodeKey(hex: String): ByteArray? =
        runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()

    /** Session de signalisation minimale : le pairage n'a besoin que du coupleId (room). */
    private fun sessionForRoom(coupleId: String, selfPublicKey: ByteArray) =
        com.bubble.shared.crypto.PairingSession(
            coupleId = coupleId,
            selfFingerprint = encodeKey(selfPublicKey).take(16),
            partnerFingerprint = "",
        )
}
