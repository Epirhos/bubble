package com.bubble.shared.widget

/**
 * Écrit l'état affichable par le widget d'écran d'accueil dans un stockage lisible hors
 * process (App Group iOS / filesDir Android), puis demande un reload du widget.
 *
 * Le widget est PUREMENT PASSIF (contrainte étape 8) : il ne fait aucun traitement, il lit
 * un snapshot déjà flouté (le flou est cuit côté émetteur) et une intensité d'Aura scalaire.
 * Aucun geste de frottement ici — le défloutage se fait après ouverture de l'app (deep link).
 */
interface WidgetBridge {
    /** Persiste l'image clé floutée (octets prêts à afficher) puis déclenche un reload. */
    suspend fun writeSnapshot(bytes: ByteArray, receivedAtMillis: Long)

    /** Persiste l'intensité d'Aura (0..1) puis déclenche un reload (opération légère). */
    suspend fun writeAura(intensity: Float)
}
