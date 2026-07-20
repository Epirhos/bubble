package com.bubble.shared.purge

import com.bubble.shared.core.DailyBubbleStatus

/**
 * Une cible effaçable (cache snapshots, canvas, files haptiques consommées, blobs relais…).
 * Chaque plateforme/module enregistre les siennes auprès du [PurgeEngine].
 */
interface PurgeTarget {
    val id: String

    suspend fun purge()
}

/** Accès au montage quotidien local — la seule donnée protégée conditionnellement. */
interface DailyBubbleStore {
    fun status(): DailyBubbleStatus

    suspend fun delete()
}

data class PurgeReport(
    val purgedTargetIds: List<String>,
    val failedTargetIds: List<String>,
    val dailyBubblePurged: Boolean,
    val dailyBubbleProtected: Boolean,
)

/**
 * Exécute la purge totale de 23h30 (voir /brain/DATA_FLOW.md §5).
 *
 * Exceptions absolues, jamais purgées ici : Daily Bubble non visionné, clés de pairage,
 * préférences (ces deux dernières ne sont simplement jamais enregistrées comme [PurgeTarget]).
 * Une cible qui échoue n'interrompt pas les autres : la purge est best-effort par cible,
 * et le relais applique de toute façon son propre TTL côté serveur.
 */
class PurgeEngine(
    private val targets: List<PurgeTarget>,
    private val dailyBubbleStore: DailyBubbleStore,
) {
    suspend fun execute(): PurgeReport {
        val purged = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (target in targets) {
            try {
                target.purge()
                purged += target.id
            } catch (_: Exception) {
                failed += target.id
            }
        }

        val status = dailyBubbleStore.status()
        val bubbleProtected = status.protectedFromPurge
        var dailyBubblePurged = false
        if (status.exists && status.viewed) {
            try {
                dailyBubbleStore.delete()
                dailyBubblePurged = true
            } catch (_: Exception) {
                failed += DAILY_BUBBLE_ID
            }
        }

        return PurgeReport(
            purgedTargetIds = purged,
            failedTargetIds = failed,
            dailyBubblePurged = dailyBubblePurged,
            dailyBubbleProtected = bubbleProtected,
        )
    }

    companion object {
        const val DAILY_BUBBLE_ID = "daily-bubble"
    }
}
