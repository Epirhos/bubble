package com.bubble.shared.signal

import com.bubble.shared.crypto.PairingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketTask

/**
 * Signalisation iOS via NSURLSession WebSocket — pendant de OkHttpSignalingClient.
 * Ne transporte que des SignalingMessage (SDP/ICE/Bye/PairingKey), jamais de contenu.
 */
class IosSignalingClient(private val serverUrl: String) : SignalingClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(SignalingState.DISCONNECTED)
    override val state: StateFlow<SignalingState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    override val inbound: Flow<SignalingMessage> = _inbound

    private var task: NSURLSessionWebSocketTask? = null

    override suspend fun connect(session: PairingSession) {
        if (_state.value != SignalingState.DISCONNECTED) return
        _state.value = SignalingState.CONNECTING
        val url = NSURL(string = "$serverUrl/room/${session.coupleId}?peer=${session.selfFingerprint}")
        val webSocket = NSURLSession.sharedSession.webSocketTaskWithURL(url)
        task = webSocket
        webSocket.resume()
        _state.value = SignalingState.CONNECTED
        receiveLoop(webSocket)
    }

    /** Ré-arme la réception après chaque message (NSURLSession livre un message à la fois). */
    private fun receiveLoop(webSocket: NSURLSessionWebSocketTask) {
        webSocket.receiveMessageWithCompletionHandler { message, error ->
            if (error != null) {
                _state.value = SignalingState.DISCONNECTED
                return@receiveMessageWithCompletionHandler
            }
            message?.string?.let { text ->
                runCatching { json.decodeFromString(SignalingMessage.serializer(), text) }
                    .onSuccess { _inbound.tryEmit(it) }
            }
            if (_state.value == SignalingState.CONNECTED) receiveLoop(webSocket)
        }
    }

    override suspend fun send(message: SignalingMessage) {
        val text = json.encodeToString(SignalingMessage.serializer(), message)
        task?.sendMessage(NSURLSessionWebSocketMessage(text)) { /* erreur ignorée : reconnexion gère */ }
    }

    override suspend fun disconnect() {
        task?.cancel()
        task = null
        _state.value = SignalingState.DISCONNECTED
    }
}
