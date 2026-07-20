package com.bubble.shared.signal

import com.bubble.shared.crypto.MessageCipher
import com.bubble.shared.crypto.PairingSession
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
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
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy
import webrtc.RTCConfiguration
import webrtc.RTCDataBuffer
import webrtc.RTCDataChannel
import webrtc.RTCDataChannelConfiguration
import webrtc.RTCDataChannelDelegateProtocol
import webrtc.RTCDataChannelState
import webrtc.RTCIceCandidate
import webrtc.RTCIceServer
import webrtc.RTCMediaConstraints
import webrtc.RTCPeerConnection
import webrtc.RTCPeerConnectionDelegateProtocol
import webrtc.RTCPeerConnectionFactory
import webrtc.RTCPeerConnectionState
import webrtc.RTCSdpType
import webrtc.RTCSessionDescription

/**
 * Tunnel WebRTC P2P iOS — pendant Kotlin/Native d'AndroidPeerLink, via cinterop direct vers
 * WebRTC.framework. Aucun pont Swift : Kotlin pilote la RTCPeerConnection et implémente les
 * delegates ObjC. Symétrie totale avec Android (protocole, E2EE, rôle, reconnexion).
 *
 * Sécurité : DTLS obligatoire (WebRTC) + [messageCipher] applicatif (ChaCha20-Poly1305,
 * commonMain) par-dessus. Le serveur de signalisation ne voit que SDP/ICE.
 *
 * NOTE : compile uniquement sur macOS (cible iosArm64/iosSimulatorArm64 + WebRTC.framework).
 */
@OptIn(ExperimentalForeignApi::class)
class IosPeerLink(
    private val signaling: SignalingClient,
    private val scope: CoroutineScope,
    private val messageCipher: MessageCipher? = null,
) : PeerLink {

    private val factory = RTCPeerConnectionFactory()

    private val _state = MutableStateFlow(PeerLinkState.IDLE)
    override val state: StateFlow<PeerLinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 64)
    override val incoming: Flow<PeerMessage> = _incoming

    private val _incomingSnapshots = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    override val incomingSnapshots: Flow<ByteArray> = _incomingSnapshots

    private var peer: RTCPeerConnection? = null
    private var metaChannel: RTCDataChannel? = null
    private var snapshotChannel: RTCDataChannel? = null
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
        if (channel.readyState() != RTCDataChannelState.RTCDataChannelStateOpen) return false
        val plain = PeerWire.encode(message).encodeToByteArray()
        val payload = messageCipher?.seal(plain) ?: plain
        return channel.sendData(RTCDataBuffer(payload.toNSData(), isBinary = false))
    }

    override fun trySendSnapshot(bytes: ByteArray): Boolean {
        val channel = snapshotChannel ?: return false
        if (channel.readyState() != RTCDataChannelState.RTCDataChannelStateOpen) return false
        val payload = messageCipher?.seal(bytes) ?: bytes
        return channel.sendData(RTCDataBuffer(payload.toNSData(), isBinary = true))
    }

    // ── Négociation ──────────────────────────────────────────────────────────────

    /** Rôle déterministe : le fingerprint le plus petit est l'initiateur (pas de collision). */
    private fun isInitiator(session: PairingSession): Boolean =
        session.selfFingerprint < session.partnerFingerprint

    private fun startNegotiation(session: PairingSession) {
        val config = RTCConfiguration().apply {
            iceServers = listOf(RTCIceServer(urlStrings = listOf("stun:stun.l.google.com:19302")))
        }
        val pc = factory.peerConnectionWithConfiguration(config, defaultConstraints(), PeerObserver())
        peer = pc
        if (isInitiator(session)) {
            openChannels(pc)
            pc.offerForConstraints(defaultConstraints()) { sdp, _ ->
                if (sdp != null) {
                    pc.setLocalDescription(sdp) { }
                    scope.launch { signaling.send(SignalingMessage.SdpOffer(sdp.sdp)) }
                }
            }
        }
    }

    private fun openChannels(pc: RTCPeerConnection) {
        val metaConfig = RTCDataChannelConfiguration().apply { isOrdered = true }
        metaChannel = pc.dataChannelForLabel(META_CHANNEL, metaConfig)?.also { it.delegate = ChannelObserver() }
        val snapConfig = RTCDataChannelConfiguration().apply {
            isOrdered = false
            maxRetransmits = 0 // périssable : le prochain snapshot remplace le perdu
        }
        snapshotChannel = pc.dataChannelForLabel(SNAPSHOT_CHANNEL, snapConfig)?.also { it.delegate = ChannelObserver() }
    }

    private fun onSignalingMessage(message: SignalingMessage) {
        val pc = peer ?: return
        when (message) {
            is SignalingMessage.SdpOffer -> {
                pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.RTCSdpTypeOffer, message.sdp)) {
                    pc.answerForConstraints(defaultConstraints()) { answer, _ ->
                        if (answer != null) {
                            pc.setLocalDescription(answer) { }
                            scope.launch { signaling.send(SignalingMessage.SdpAnswer(answer.sdp)) }
                        }
                    }
                }
            }
            is SignalingMessage.SdpAnswer ->
                pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.RTCSdpTypeAnswer, message.sdp)) { }
            is SignalingMessage.IceCandidate ->
                pc.addIceCandidate(RTCIceCandidate(message.candidate, message.sdpMLineIndex, message.sdpMid))
            SignalingMessage.Bye -> {
                teardownPeer()
                _state.value = PeerLinkState.DISCONNECTED
            }
            is SignalingMessage.PairingKey -> Unit // couche pairage, pas ici
        }
    }

    private fun attachIncomingChannel(channel: RTCDataChannel) {
        when (channel.label()) {
            META_CHANNEL -> metaChannel = channel
            SNAPSHOT_CHANNEL -> snapshotChannel = channel
            else -> return
        }
        channel.delegate = ChannelObserver()
    }

    private fun handleMessage(bytes: ByteArray, binary: Boolean) {
        // Cipher présent + tag invalide → message rejeté (pas de repli sur le chiffré).
        val plain = if (messageCipher != null) messageCipher.open(bytes) ?: return else bytes
        if (binary) {
            _incomingSnapshots.tryEmit(plain)
        } else {
            runCatching { PeerWire.decode(plain.decodeToString()) }.onSuccess { _incoming.tryEmit(it) }
        }
    }

    // ── Delegates ObjC (implémentés en Kotlin/Native) ────────────────────────────

    private inner class PeerObserver : NSObject(), RTCPeerConnectionDelegateProtocol {
        override fun peerConnection(peerConnection: RTCPeerConnection, didGenerateIceCandidate: RTCIceCandidate) {
            scope.launch {
                signaling.send(
                    SignalingMessage.IceCandidate(
                        didGenerateIceCandidate.sdp,
                        didGenerateIceCandidate.sdpMid,
                        didGenerateIceCandidate.sdpMLineIndex,
                    ),
                )
            }
        }

        override fun peerConnection(peerConnection: RTCPeerConnection, didOpenDataChannel: RTCDataChannel) {
            attachIncomingChannel(didOpenDataChannel)
        }

        override fun peerConnection(peerConnection: RTCPeerConnection, didChangeConnectionState: RTCPeerConnectionState) {
            when (didChangeConnectionState) {
                RTCPeerConnectionState.RTCPeerConnectionStateFailed,
                RTCPeerConnectionState.RTCPeerConnectionStateDisconnected,
                -> scheduleReconnect()
                else -> Unit
            }
        }
    }

    private inner class ChannelObserver : NSObject(), RTCDataChannelDelegateProtocol {
        override fun dataChannelDidChangeState(dataChannel: RTCDataChannel) {
            if (dataChannel.label() == META_CHANNEL && dataChannel.readyState() == RTCDataChannelState.RTCDataChannelStateOpen) {
                reconnectAttempt = 0
                _state.value = PeerLinkState.CONNECTED
            }
        }

        override fun dataChannel(dataChannel: RTCDataChannel, didReceiveMessageWithBuffer: RTCDataBuffer) {
            handleMessage(didReceiveMessageWithBuffer.data.toByteArray(), didReceiveMessageWithBuffer.isBinary)
        }
    }

    // ── Reconnexion (backoff exponentiel + jitter, comme Android) ────────────────

    private fun scheduleReconnect() {
        val current = session ?: return
        if (closedByUser) return
        _state.value = PeerLinkState.DISCONNECTED
        val attempt = reconnectAttempt++
        scope.launch {
            val backoff = min(BASE_BACKOFF_MILLIS shl min(attempt, 5), MAX_BACKOFF_MILLIS)
            delay(backoff + Random.nextLong(JITTER_MILLIS))
            if (closedByUser || _state.value == PeerLinkState.CONNECTED) return@launch
            teardownPeer()
            _state.value = PeerLinkState.CONNECTING
            startNegotiation(current)
        }
    }

    private fun teardownPeer() {
        metaChannel?.close(); snapshotChannel?.close()
        metaChannel = null; snapshotChannel = null
        peer?.close()
        peer = null
    }

    private fun defaultConstraints() =
        RTCMediaConstraints(mandatoryConstraints = null, optionalConstraints = null)

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = if (isEmpty()) null else pinned.addressOf(0), length = size.convert())
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val out = ByteArray(size)
        if (size > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        return out
    }

    private companion object {
        const val META_CHANNEL = "bubble-meta"
        const val SNAPSHOT_CHANNEL = "bubble-snapshots"
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val JITTER_MILLIS = 500L
    }
}
