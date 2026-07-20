package com.bubble.shared.media

/**
 * Un fragment éphémère de la journée : une image clé (snapshot) ou une trace de canvas,
 * déjà stockée en clair localement (jamais montée sur un cloud). Le compresseur natif la
 * lira puis la libérera immédiatement.
 */
data class DailyFragment(
    val localPath: String,
    val capturedAtEpochMillis: Long,
)

/** Ambiance appliquée au montage (filtre doux — Calm Technology, pas d'effet clinquant). */
enum class DailyBubbleMood { WARM_DUSK, SOFT_FILM, MOONLIGHT }

/** Réglages de compilation du montage accéléré. */
data class DailyBubbleSpec(
    val fps: Int = 12,
    /** Durée cible du time-lapse fini ; les fragments sont ré-échantillonnés pour l'atteindre. */
    val targetDurationSeconds: Int = 15,
    val mood: DailyBubbleMood = DailyBubbleMood.WARM_DUSK,
    val width: Int = 720,
    val height: Int = 1280,
)

/** Issue de la compilation. */
sealed interface DailyBubbleResult {
    data class Success(val outputPath: String, val durationMillis: Long) : DailyBubbleResult

    /** Aucun fragment exploitable : pas de montage ce jour-là. */
    data object Empty : DailyBubbleResult

    data class Failure(val reason: String) : DailyBubbleResult
}

/**
 * Compresseur de journée — implémenté nativement (MediaCodec/MediaMuxer sur Android,
 * AVAssetWriter sur iOS). 100 % on-device : aucun fragment brut ne quitte l'appareil.
 *
 * Contrat de performance (contrainte étape 7) : l'implémentation traite les frames en flux
 * (une à la fois), libère chaque bitmap/CVPixelBuffer immédiatement après encodage, et ne
 * garde jamais toute la journée en RAM. L'appel est suspendant et tourne hors Main Thread.
 */
interface DailyBubbleComposer {
    suspend fun compile(
        fragments: List<DailyFragment>,
        outputPath: String,
        spec: DailyBubbleSpec = DailyBubbleSpec(),
    ): DailyBubbleResult
}

/** Fournit les fragments de la journée écoulée (cache local). Implémentation native. */
interface DailyFragmentSource {
    suspend fun fragmentsForToday(): List<DailyFragment>
}

/**
 * Stockage sécurisé du montage : le fichier est chiffré au repos (Keystore/Keychain).
 * Sépare le chiffrement du reste — le PurgeEngine ne connaît que [com.bubble.shared.purge.DailyBubbleStore].
 */
interface SecureDailyBubbleStore {
    /** Chiffre et range le montage compilé ; retourne le chemin du fichier chiffré. */
    suspend fun persist(plainOutputPath: String): String

    /** Déchiffre vers un chemin lisible le temps du visionnage (fichier temporaire). */
    suspend fun openForPlayback(): String?

    suspend fun delete()

    fun exists(): Boolean
}
