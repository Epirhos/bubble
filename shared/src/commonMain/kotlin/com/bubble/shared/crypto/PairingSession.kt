package com.bubble.shared.crypto

import kotlinx.serialization.Serializable

/** Canal utilisé pour l'échange initial des clés (même cérémonie, support différent). */
@Serializable
enum class PairingMethod { QR_CODE, NUMERIC_CODE }

/**
 * Session de pairage 1:1 établie par échange de clés X25519 via QR ou code numérique
 * (TOFU + vérification manuelle du fingerprint).
 *
 * INVARIANT PRODUIT : un compte n'est jamais lié à plus d'un autre compte. Tant qu'une
 * session existe, tout nouveau pairage est refusé — la désaffiliation (rupture du lien,
 * avec purge immédiate) est un préalable obligatoire. Appliqué par `BubbleStateManager.onPaired`.
 *
 * Ce modèle ne contient QUE des identifiants publics : les clés privées vivent dans le
 * Keystore/Keychain natif et ne traversent jamais la frontière du module shared.
 */
@Serializable
data class PairingSession(
    /** Identifiant opaque du couple côté relais (aucune donnée personnelle). */
    val coupleId: String,
    val selfFingerprint: String,
    val partnerFingerprint: String,
    val method: PairingMethod = PairingMethod.QR_CODE,
)
