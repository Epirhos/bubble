package com.bubble.shared.play

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Déclencheurs de "Jeux de Complicité" (Calm Tech). Transitent par le même canal E2EE que
 * le reste (enveloppés dans [com.bubble.shared.signal.SignalPayload.Playful]).
 *
 * RÈGLE ABSOLUE : aucun de ces types n'est un message texte de chat. Ils ne font que piloter
 * l'UI existante (Canvas manuscrit, Aura, Moteur Haptique) et respectent l'éphémérité (tout
 * est effacé à 23h30). Aucun historique, aucun accusé de lecture, aucun perdant.
 */
@Serializable
sealed interface PlayfulPayload {
    /**
     * Pousse un dilemme / une phrase incomplète en filigrane gravé sur le Canvas.
     * Les partenaires répondent UNIQUEMENT en dessinant par-dessus (le tracé s'écrase
     * comme tout Canvas). [text] est une amorce, jamais une conversation.
     */
    @Serializable
    @SerialName("play_prompt")
    data class CanvasPrompt(val text: String, val promptId: String? = null) : PlayfulPayload

    /**
     * Radar Haptique : coordonnées normalisées (0..1) d'un point caché sur l'écran de
     * l'émetteur. INVISIBLE chez le récepteur — il le cherche au doigt, guidé par l'haptique.
     */
    @Serializable
    @SerialName("play_radar")
    data class HapticRadar(val x: Float, val y: Float) : PlayfulPayload

    /** "Bon pour…" / mini-défi doux, révélé au tap puis évaporé à la lecture. */
    @Serializable
    @SerialName("play_coupon")
    data class ActionCoupon(val text: String) : PlayfulPayload
}
