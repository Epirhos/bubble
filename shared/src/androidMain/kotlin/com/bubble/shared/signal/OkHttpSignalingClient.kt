package com.bubble.shared.signal

import com.bubble.shared.crypto.PairingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Signalisation WebSocket (OkHttp) vers le serveur minimaliste (server/signaling-server.js).
 * Ne transporte QUE des SignalingMessage (SDP/ICE/Bye) — jamais de contenu.
 * Room = coupleId ; le serveur n'accepte que 2 pairs et ne stocke rien.
 */
class OkHttpSignalingClient(
    private val serverUrl: String, // ex. wss://relay.bubble.app ou ws://10.0.2.2:8787 en dev
    private val client: OkHttpClient = OkHttpClient(),
) : SignalingClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(SignalingState.DISCONNECTED)
    override val state: StateFlow<SignalingState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    override val inbound: Flow<SignalingMessage> = _inbound

    private var socket: WebSocket? = null

    override suspend fun connect(session: PairingSession) {
        if (_state.value != SignalingState.DISCONNECTED) return
        _state.value = SignalingState.CONNECTING
        val request = Request.Builder()
            .url("$serverUrl/room/${session.coupleId}?peer=${session.selfFingerprint}")
            .build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value = SignalingState.CONNECTED
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { json.decodeFromString(SignalingMessage.serializer(), text) }
                        .onSuccess { _inbound.tryEmit(it) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _state.value = SignalingState.DISCONNECTED
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _state.value = SignalingState.DISCONNECTED
                }
            },
        )
    }

    override suspend fun send(message: SignalingMessage) {
        socket?.send(json.encodeToString(SignalingMessage.serializer(), message))
    }

    override suspend fun disconnect() {
        socket?.close(NORMAL_CLOSURE, "bye")
        socket = null
        _state.value = SignalingState.DISCONNECTED
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
