package com.bubble.shared.media

import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.purge.PurgeEngine

/**
 * Orchestre le cycle temporel quotidien (étape 7) entre le moteur, le compresseur natif,
 * le stockage chiffré et le PurgeEngine. Sans logique de fichiers : elle vit dans les
 * implémentations natives derrière les interfaces. Testable en commonMain avec des fakes.
 *
 * Les schedulers natifs (WorkManager / BGTaskScheduler) appellent [generateDailyBubble] à
 * 21h locale et [runPurge] à l'instant de purge (manager.nextPurgeInstant, fuseau le plus tardif).
 */
class DailyBubbleCoordinator(
    private val manager: BubbleStateManager,
    private val fragmentSource: DailyFragmentSource,
    private val composer: DailyBubbleComposer,
    private val secureStore: SecureDailyBubbleStore,
    private val purgeEngine: PurgeEngine,
    /** Chemin de travail (clair, éphémère) où le composer écrit avant chiffrement. */
    private val workingOutputPath: String,
) {
    /**
     * 21h : compile le montage de la journée puis le range chiffré.
     * Le fichier clair de travail est supprimé après persistance (rien en clair ne survit).
     */
    suspend fun generateDailyBubble(spec: DailyBubbleSpec = DailyBubbleSpec()): DailyBubbleResult {
        manager.onDailySummaryStarted()
        val fragments = fragmentSource.fragmentsForToday()
        if (fragments.isEmpty()) {
            manager.onDailySummaryEmpty()
            return DailyBubbleResult.Empty
        }
        return when (val result = composer.compile(fragments, workingOutputPath, spec)) {
            is DailyBubbleResult.Success -> {
                secureStore.persist(result.outputPath) // chiffre + supprime le clair
                manager.onDailyBubbleGenerated()
                result
            }
            DailyBubbleResult.Empty -> {
                manager.onDailySummaryEmpty()
                result
            }
            is DailyBubbleResult.Failure -> {
                manager.onDailySummaryEmpty()
                result
            }
        }
    }

    /**
     * Instant de purge : efface tout, SAUF le Daily Bubble non visionné (jalousement conservé).
     * Le [PurgeEngine] applique déjà l'exception ; on ne fait que piloter les transitions d'état.
     */
    suspend fun runPurge() {
        manager.onPurgeDue()
        if (!manager.onPurgeStarted()) return // différée (session Live en cours)
        purgeEngine.execute()
        manager.onPurgeCompleted()
    }

    /**
     * L'utilisateur visionne le montage conservé : suppression définitive du fichier chiffré
     * puis réinitialisation sur la page blanche.
     */
    suspend fun onDailyBubbleWatched() {
        manager.onDailyBubbleViewed()
        secureStore.delete()
        manager.onDailyBubblePurged()
    }
}
