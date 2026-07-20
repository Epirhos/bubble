package com.bubble.shared.signal

import android.content.Context
import com.bubble.shared.crypto.MessageCipher
import com.bubble.shared.crypto.PairingSession
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Tunnel WebRTC Android (libwebrtc Google via stream-webrtc-android).
 *
 * Sécurité : tout DataChannel WebRTC est chiffré par DTLS 1.2+ — ce n'est pas une option,
 * la spec l'impose et libwebrtc refuse les transports en clair. Le serveur de signalisation
 * ne voit que SDP/ICE ; la clé de session DTLS est négociée de pair à pair.
 *
 * Deux canaux :
 *  - "bubble-meta"      : fiable/ordonné — triggers ML, présence, Hello (Timezone Sync).
 *  - "bubble-snapshots" : non fiable/non ordonné (maxRetransmits=0) — snapshots floutés
 *    chiffrés E2EE ; un snapshot perdu est sans importance, le suivant le remplace.
 *
 * Rôles déterministes : le pair au fingerprint le plus petit est l'initiateur (offer +
 * création des canaux) ; l'autre répond. Pas de collision de négociation possible.
 *
 * Reconnexion : sur FAILED/DISCONNECTED, renégociation complète avec backoff exponentiel
 * + jitter (1 s → 30 s max), tant que [disconnect] n'a pas été demandé.
 */
class AndroidPeerLink(
    context: Context,
    private val signaling: SignalingClient,
    private val scope: CoroutineScope,
    private val rtcConfig: RtcConfiguration = RtcConfiguration.DEFAULT,
    /** E2EE applicatif : scelle/déscelle chaque payload. null = clair (dev/loopback). */
    private val messageCipher: MessageCipher? = null,
) : PeerLink {

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
    }

    private val factory: PeerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

    private val _state = MutableStateFlow(PeerLinkState.IDLE)
    override val state: StateFlow<PeerLinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 64)
    override val incoming: Flow<PeerMessage> = _incoming

    private val _incomingSnapshots = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    override val incomingSnapshots: Flow<ByteArray> = _incomingSnapshots

    private var peerConnection: PeerConnection? = null
    private var metaChannel: DataChannel? = null
    private var snapshotChannel: DataChannel? = null
    private var session: PairingSession? = null
    private var signalingJob: Job? = null
    private var reconnectAttempt = 0
    private var closedByUser = false

    override suspend fun connect(session: PairingSession) {
        this.session = session
        closedByUser = false
        _state.value = PeerLinkState.CONNECTING
        signaling.connect(session)
        if (signalingJob == null) {
            signalingJob = scope.launch { signaling.inbound.collect(::onSignalingMessage) }
        }
        startNegotiation(session)
    }

    override suspend fun disconnect() {
        closedByUser = true
        teardownPeer()
        signalingJob?.cancel()
        signalingJob = null
        signaling.send(SignalingMessage.Bye)
        signaling.disconnect()
        _state.value = PeerLinkState.CLOSED
    }

    override fun trySend(message: PeerMessage): Boolean {
        val channel = metaChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val plain = PeerWire.encode(message).encodeToByteArray()
        val payload = messageCipher?.seal(plain) ?: plain
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), false))
    }

    override fun trySendSnapshot(bytes: ByteArray): Boolean {
        val channel = snapshotChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val payload = messageCipher?.seal(bytes) ?: bytes
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), true))
    }

    // ── Négociation ────────────────────────────────────────────────────────────

    private fun isInitiator(session: PairingSession): Boolean =
        session.selfFingerprint < session.partnerFingerprint

    private fun startNegotiation(session: PairingSession) {
        val ice = rtcConfig.iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .apply {
                    server.username?.let(::setUsername)
                    server.credential?.let(::setPassword)
                }
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(config, PeerObserver())
        if (isInitiator(session)) {
            openChannels()
            sendOffer()
        }
    }

    private fun openChannels() {
        val pc = peerConnection ?: return
        attachChannel(pc.createDataChannel(META_CHANNEL, DataChannel.Init().apply { ordered = true }))
        attachChannel(
            pc.createDataChannel(
                SNAPSHOT_CHANNEL,
                DataChannel.Init().apply {
                    ordered = false
                    maxRetransmits = 0 // périssable : le prochain snapshot remplace le perdu
                },
            ),
        )
    }

    private fun sendOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(
            object : SdpAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    pc.setLocalDescription(SdpAdapter(), description)
                    scope.launch { signaling.send(SignalingMessage.SdpOffer(description.description)) }
                }
            },
            MediaConstraints(),
        )
    }

    private fun onSignalingMessage(message: SignalingMessage) {
        val pc = peerConnection ?: return
        when (message) {
            is SignalingMessage.SdpOffer -> {
                pc.setRemoteDescription(
                    object : SdpAdapter() {
                        override fun onSetSuccess() {
                            pc.createAnswer(
                                object : SdpAdapter() {
                                    override fun onCreateSuccess(description: SessionDescription) {
                                        pc.setLocalDescription(SdpAdapter(), description)
                                        scope.launch {
                                            signaling.send(SignalingMessage.SdpAnswer(description.description))
                                        }
                                    }
                                },
                                MediaConstraints(),
                            )
                        }
                    },
                    SessionDescription(SessionDescription.Type.OFFER, message.sdp),
                )
            }

            is SignalingMessage.SdpAnswer ->
                pc.setRemoteDescription(SdpAdapter(), SessionDescription(SessionDescription.Type.ANSWER, message.sdp))

            is SignalingMessage.IceCandidate ->
                pc.addIceCandidate(IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate))

            SignalingMessage.Bye -> {
                teardownPeer()
                _state.value = PeerLinkState.DISCONNECTED
            }

            is SignalingMessage.PairingKey -> Unit // consommé par la couche pairage, pas ici
        }
    }

    // ── Canaux & état ──────────────────────────────────────────────────────────

    private fun attachChannel(channel: DataChannel) {
        when (channel.label()) {
            META_CHANNEL -> metaChannel = channel
            SNAPSHOT_CHANNEL -> snapshotChannel = channel
            else -> return // canal inconnu : ignoré, le protocole est fermé
        }
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    if (channel.label() == META_CHANNEL && channel.state() == DataChannel.State.OPEN) {
                        reconnectAttempt = 0
                        _state.value = PeerLinkState.CONNECTED
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val raw = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    // Cipher présent + tag invalide → message rejeté (pas de repli sur le chiffré).
                    val plain = if (messageCipher != null) messageCipher.open(raw) ?: return else raw
                    if (buffer.binary) {
                        _incomingSnapshots.tryEmit(plain)
                    } else {
                        runCatching { PeerWire.decode(plain.decodeToString()) }
                            .onSuccess { _incoming.tryEmit(it) }
                    }
                }
            },
        )
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                signaling.send(
                    SignalingMessage.IceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex),
                )
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED,
                -> scheduleReconnect()

                else -> Unit // CONNECTED est signalé par l'ouverture du canal meta
            }
        }

        override fun onDataChannel(channel: DataChannel) = attachChannel(channel)

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onRenegotiationNeeded() = Unit
    }

    // ── Reconnexion ────────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        val currentSession = session ?: return
        if (closedByUser) return
        _state.value = PeerLinkState.DISCONNECTED
        val attempt = reconnectAttempt++
        scope.launch {
            val backoffMillis = min(BASE_BACKOFF_MILLIS shl min(attempt, 5), MAX_BACKOFF_MILLIS)
            delay(backoffMillis + Random.nextLong(JITTER_MILLIS))
            if (closedByUser || _state.value == PeerLinkState.CONNECTED) return@launch
            teardownPeer()
            _state.value = PeerLinkState.CONNECTING
            startNegotiation(currentSession)
        }
    }

    private fun teardownPeer() {
        metaChannel?.close()
        snapshotChannel?.close()
        metaChannel = null
        snapshotChannel = null
        peerConnection?.close()
        peerConnection = null
    }

    /** Adaptateur no-op : chaque étape de négociation ne surcharge que ce qui l'intéresse. */
    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) = Unit

        override fun onSetFailure(error: String?) = Unit
    }

    private companion object {
        const val META_CHANNEL = "bubble-meta"
        const val SNAPSHOT_CHANNEL = "bubble-snapshots"
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val JITTER_MILLIS = 500L
    }
}
