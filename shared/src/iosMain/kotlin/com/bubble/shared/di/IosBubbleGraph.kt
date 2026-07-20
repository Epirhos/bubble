package com.bubble.shared.di

import com.bubble.shared.core.AuraGlow
import com.bubble.shared.core.BubbleState
import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.core.PairingEvent
import com.bubble.shared.crypto.AeadMessageCipher
import com.bubble.shared.crypto.IosPairedIdentityStore
import com.bubble.shared.crypto.PairedIdentity
import com.bubble.shared.crypto.PairingHandshake
import com.bubble.shared.crypto.PairingResult
import com.bubble.shared.crypto.PairingSession
import com.bubble.shared.haptics.IosHapticEngine
import com.bubble.shared.signal.IosPeerLink
import com.bubble.shared.signal.IosSignalingClient
import com.bubble.shared.signal.LinkCoordinator
import com.bubble.shared.signal.SignalingClient
import com.bubble.shared.signal.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT

/**
 * Racine de composition iOS — pendant de BubbleApplication (Android). Instancie une fois le
 * moteur KMP, l'haptique CoreHaptics, le stockage Keychain, et gère le cycle de vie du lien
 * P2P E2EE (établi au pairage, restauré au démarrage, coupé à la désaffiliation).
 *
 * Exposé à Swift via SKIE. Le `signaling` est partagé avec la cérémonie de pairage.
 */
class IosBubbleGraph(private val signalingUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val manager = BubbleStateManager(scope = scope, hapticEngine = IosHapticEngine())

    /** Partagé avec le pairage : le lien P2P réutilise cette connexion (même room). */
    val signaling: SignalingClient = IosSignalingClient(signalingUrl)

    private val identityStore = IosPairedIdentityStore()
    private var link: IosPeerLink? = null

    // Flux dérivés SIMPLES (bool) pour épargner à Swift la gestion des sealed via SKIE.
    private val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

    /** true = fusion (BubblesMerged), false = scission (BubbleSplit) — pour l'animation. */
    private val _ritualMerged = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val ritualMerged: SharedFlow<Boolean> = _ritualMerged.asSharedFlow()

    init {
        scope.launch { manager.state.collect { _isLive.value = it is BubbleState.Live } }
        scope.launch {
            manager.pairingEvents.collect { event ->
                _ritualMerged.tryEmit(event is PairingEvent.BubblesMerged)
                if (event is PairingEvent.BubbleSplit) teardown()
            }
        }
    }

    /** Intensité d'Aura à l'instant courant (évite d'exposer Instant à Swift). */
    fun auraIntensity(aura: AuraGlow): Float = aura.intensityAt(Clock.System.now())

    // ── Cérémonie de pairage (orchestrée en Kotlin, état simple pour Swift) ──────

    private val _pairing = MutableStateFlow(IosPairingState(PairingPhase.CHOICE))
    val pairing: StateFlow<IosPairingState> = _pairing.asStateFlow()
    private var handshake: PairingHandshake? = null

    /** Émetteur : génère l'invitation (QR), ouvre la room, attend la clé du partenaire. */
    fun startInvite() {
        val hs = PairingHandshake.initiator()
        handshake = hs
        _pairing.value = IosPairingState(PairingPhase.SHOWING_INVITE, qrPayload = hs.invite.encode())
        scope.launch {
            signaling.connect(roomSession(hs.invite.coupleId, hs.publicKeyEncoded))
            signaling.inbound.collect { msg ->
                if (msg is SignalingMessage.PairingKey) {
                    hs.complete(msg.publicKey)?.let { finishPairing(it) }
                }
            }
        }
    }

    fun startScanning() {
        _pairing.value = IosPairingState(PairingPhase.SCANNING)
    }

    /** Récepteur : QR scanné → dérive la session, renvoie SA clé publique à l'émetteur. */
    fun onQrScanned(raw: String) {
        val result = PairingHandshake.responder(raw)
        if (result == null) {
            _pairing.value = IosPairingState(PairingPhase.ERROR, message = "Invitation invalide")
            return
        }
        val hs = result.first
        handshake = hs
        scope.launch {
            signaling.connect(roomSession(hs.invite.coupleId, hs.publicKeyEncoded))
            signaling.send(SignalingMessage.PairingKey(hs.publicKeyEncoded))
            finishPairing(result.second)
        }
    }

    private fun finishPairing(result: PairingResult) {
        if (manager.onPaired(result.session)) {
            onPaired(result)
            _pairing.value = IosPairingState(PairingPhase.PAIRED, safetyNumber = result.safetyNumber)
        } else {
            _pairing.value = IosPairingState(PairingPhase.ERROR, message = "Déjà lié — désaffilier d'abord")
        }
    }

    /** Session de signalisation minimale : le pairage n'a besoin que du coupleId (room). */
    private fun roomSession(coupleId: String, selfPublicKeyEncoded: String) =
        PairingSession(coupleId, selfFingerprint = selfPublicKeyEncoded.take(16), partnerFingerprint = "")

    /** Démarrage : rétablit un couple déjà pairé (identité Keychain) sans refaire la cérémonie. */
    fun restore() {
        scope.launch {
            val identity = identityStore.load() ?: return@launch
            if (manager.onPaired(identity.session)) attach(identity, persist = false)
        }
    }

    /** Pairage réussi (appelé depuis Swift) : persiste + monte le lien E2EE. */
    fun onPaired(result: PairingResult) {
        scope.launch { attach(PairedIdentity(result.session, result.sessionKey), persist = true) }
    }

    private suspend fun attach(identity: PairedIdentity, persist: Boolean) {
        if (persist) identityStore.save(identity)
        val peer = IosPeerLink(signaling, scope, AeadMessageCipher(identity.sessionKey))
        LinkCoordinator(
            scope = scope,
            manager = manager,
            link = peer,
            selfUtcOffsetMinutes = { selfUtcOffsetMinutes() },
        ).start()
        scope.launch { peer.connect(identity.session) }
        link = peer
    }

    private fun teardown() {
        val current = link
        link = null
        scope.launch { current?.disconnect() }
    }

    fun selfUtcOffsetMinutes(): Int = (NSTimeZone.localTimeZone.secondsFromGMT / 60).toInt()
}
