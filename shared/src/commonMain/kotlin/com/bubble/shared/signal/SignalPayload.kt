package com.bubble.shared.signal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Geste non verbal détecté localement (MediaPipe/CoreML) et rejoué en haptique chez le partenaire.
 */
@Serializable
enum class HapticGesture {
    KISS,
    HEART,
}

/**
 * Unité de communication entre les deux appareils du couple.
 *
 * Invariants protocole :
 *  - Aucun message d'accusé de lecture n'existe dans cette hiérarchie — c'est volontaire et définitif.
 *  - Les images ne transitent jamais inline : seulement des [blobKey] pointant vers des blobs E2EE
 *    déposés sur le relais aveugle.
 */
@Serializable
sealed interface SignalPayload {
    /** Horodatage d'émission (epoch millis UTC), fixé par l'émetteur. */
    val sentAtEpochMillis: Long

    /** Image clé floutée du Portail Live, déjà chiffrée et déposée sur le relais. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        override val sentAtEpochMillis: Long,
        val blobKey: String,
    ) : SignalPayload

    /**
     * Tracé/photo du Canvas Créatif. [blobKey] est FIXE par couple :
     * chaque dépôt écrase le précédent (last-write-wins), aucun historique.
     */
    @Serializable
    @SerialName("canvas")
    data class CanvasTrace(
        override val sentAtEpochMillis: Long,
        val blobKey: String,
    ) : SignalPayload

    /** Signal haptique (bisou, cœur). Payload minimal, jamais d'image. */
    @Serializable
    @SerialName("haptic")
    data class Haptic(
        override val sentAtEpochMillis: Long,
        val gesture: HapticGesture,
    ) : SignalPayload

    /**
     * Statut de présence pour l'Aura d'Activité. Throttlé côté émetteur (max 1/min).
     * Ne transporte jamais de contenu ni d'historique.
     */
    @Serializable
    @SerialName("presence")
    data class Presence(
        override val sentAtEpochMillis: Long,
        val active: Boolean,
    ) : SignalPayload

    /** Déclencheur de Jeu de Complicité (voir [com.bubble.shared.play.PlayfulPayload]). */
    @Serializable
    @SerialName("playful")
    data class Playful(
        override val sentAtEpochMillis: Long,
        val payload: com.bubble.shared.play.PlayfulPayload,
    ) : SignalPayload
}

/** Encodage/décodage du protocole. Le JSON produit est chiffré (E2EE) avant tout transit réseau. */
object SignalWire {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: SignalPayload): String = json.encodeToString(SignalPayload.serializer(), payload)

    fun decode(raw: String): SignalPayload = json.decodeFromString(SignalPayload.serializer(), raw)
}
