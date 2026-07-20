package com.bubble.shared.core

import com.bubble.shared.crypto.PairingSession
import kotlinx.datetime.Instant

/**
 * Événements ponctuels du lien de couple. Les UI natives les consomment pour jouer les
 * rituels d'animation — la sémantique est fixée ici, la chorégraphie appartient au natif.
 */
sealed interface PairingEvent {
    /** Affiliation réussie → animation : deux bulles se rejoignent et fusionnent en une seule. */
    data class BubblesMerged(val session: PairingSession) : PairingEvent

    /** Rupture du lien → animation : la bulle se scinde en deux bulles qui s'éloignent. */
    data object BubbleSplit : PairingEvent
}

/**
 * États exclusifs de l'application (voir /brain/STATE_MACHINE.md).
 *
 * Note d'implémentation : `EchoPending` et `DailyBubbleReady` du cahier des charges ne sont PAS
 * des états exclusifs — des échos peuvent être en attente pendant qu'on est en Ambient ou Live.
 * Ils sont modélisés comme des flux orthogonaux ([EchoQueue], [DailyBubbleStatus]) portés par
 * [BubbleStateManager].
 */
sealed interface BubbleState {
    /** Aucune clé partenaire : seul le pairage est possible. */
    data object Unpaired : BubbleState

    /** État par défaut : widget passif, Aura mise à jour via push de présence. */
    data object Ambient : BubbleState

    /** Portail ouvert depuis le widget (deep link) : frottement/défloutage in-app, gestes actifs. */
    data class Live(val since: Instant) : BubbleState

    /**
     * L'instant de purge (23h30, fuseau le plus tardif) est atteint mais l'exécution attend
     * (ex. session Live en cours — on ne coupe jamais un moment partagé).
     */
    data object PurgePending : BubbleState

    /** Purge en cours d'exécution (locale + relais). */
    data object Purging : BubbleState
}

/** Raison de sortie de l'état Live. */
enum class LiveEndReason {
    USER_EXIT,
    BACKGROUND,

    /** FaceWatchdog : 120 s sans visage détecté. */
    NO_FACE_TIMEOUT,
}

/**
 * Lueur d'activité du partenaire. [lastPresenceAt] null = aura neutre.
 * L'intensité décroît linéairement sur [AuraGlow.DECAY_MINUTES] minutes ; le rendu appelle
 * [intensityAt] avec l'heure courante.
 */
data class AuraGlow(val lastPresenceAt: Instant?) {
    fun intensityAt(now: Instant): Float {
        val last = lastPresenceAt ?: return 0f
        val elapsedMinutes = (now - last).inWholeSeconds / 60f
        return (1f - elapsedMinutes / DECAY_MINUTES).coerceIn(0f, 1f)
    }

    companion object {
        const val DECAY_MINUTES = 15f
        val NEUTRAL = AuraGlow(lastPresenceAt = null)
    }
}

/** État local du montage quotidien ; conditionne l'exception de purge. */
data class DailyBubbleStatus(val exists: Boolean, val viewed: Boolean) {
    val protectedFromPurge: Boolean get() = exists && !viewed

    companion object {
        val NONE = DailyBubbleStatus(exists = false, viewed = false)
    }
}

/**
 * Phase du cycle temporel quotidien — flux orthogonal aux états exclusifs [BubbleState]
 * (la génération à 21h ne coupe pas une session Live, la purge s'y superpose).
 * Les trois valeurs non-IDLE sont celles du cahier des charges (étape 7).
 */
enum class DailyPhase {
    /** Rien en cours : journée normale, ou page blanche après visionnage. */
    IDLE,

    /** 21h : le compresseur de journée assemble le montage accéléré (worker natif). */
    GENERATING_SUMMARY,

    /** 23h30 (fuseau le plus tardif) atteint : la purge globale va s'exécuter. */
    PURGE_READY,

    /** Post-purge : tout est effacé SAUF le Daily Bubble non visionné, jalousement conservé. */
    PERSISTED_UNWATCHED,
}
