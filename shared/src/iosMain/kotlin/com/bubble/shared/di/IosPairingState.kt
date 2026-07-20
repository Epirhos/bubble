package com.bubble.shared.di

/** Phase visible de la cérémonie (enum simple → SKIE l'expose en enum Swift). */
enum class PairingPhase { CHOICE, SHOWING_INVITE, SCANNING, PAIRED, ERROR }

/**
 * État plat de la cérémonie de pairage, exposé à Swift. Pas de sealed : la vue lit
 * `phase` + les champs optionnels selon le cas. Toute la logique reste dans [IosBubbleGraph].
 */
data class IosPairingState(
    val phase: PairingPhase,
    val qrPayload: String? = null,
    val safetyNumber: String? = null,
    val message: String? = null,
)
