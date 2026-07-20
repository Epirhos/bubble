package com.bubble.shared.signal

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction du canal de signalisation (push silencieux + WebRTC data channel plus tard).
 * Le moteur ne connaît que cette interface ; l'implémentation réelle viendra dans une étape dédiée.
 */
interface SignalTransport {
    /** Flux des payloads entrants, déjà déchiffrés (E2EE géré sous cette interface). */
    val incoming: Flow<SignalPayload>

    /** Envoi fire-and-forget : pas de retour de statut de lecture, par construction. */
    suspend fun send(payload: SignalPayload)
}

/**
 * Abstraction du relais de blobs opaques (snapshots/canvas chiffrés).
 * Le relais ne voit jamais de clair ; il applique aussi son propre TTL indépendant du client.
 */
interface BlobRelay {
    suspend fun put(key: String, encryptedBytes: ByteArray)

    /** null si le blob a expiré (TTL) ou a été purgé. */
    suspend fun get(key: String): ByteArray?

    /** Purge serveur de tous les blobs du couple (appelée par le PurgeEngine à 23h30). */
    suspend fun purgeAll()
}
