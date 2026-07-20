package com.bubble.shared.ml

/**
 * Politique d'échantillonnage adaptatif de la boucle vision (économie CPU/batterie).
 *
 * Deux régimes :
 *  - IDLE (défaut) : 2 Hz, modèle visage seul — suffit au watchdog 120 s et à l'Aura.
 *    Le modèle mains (le plus coûteux) n'est sondé qu'une fois par [handProbeIntervalMillis]
 *    pour détecter l'entrée d'une main dans le champ.
 *  - ACTIVE : ~7 Hz, visage + mains — dès qu'une main est vue ; le debouncer a besoin de
 *    cette cadence pour confirmer un geste en ~450 ms.
 *  Retour à IDLE après [activeHoldMillis] sans main (le couple se regarde, simplement).
 *
 * Le coût moyen d'une session Live chute de ~3-4× : l'essentiel du temps se passe en IDLE.
 * Cette classe est le SEUL endroit qui décide de la cadence — jamais de seuils en natif.
 * Non thread-safe : à utiliser depuis l'unique thread d'inférence.
 */
class AdaptiveSamplingPolicy(
    private val idleIntervalMillis: Long = 500,
    private val activeIntervalMillis: Long = 150,
    private val handProbeIntervalMillis: Long = 1_000,
    private val activeHoldMillis: Long = 3_000,
) {
    enum class Mode { IDLE, ACTIVE }

    /** Ce qu'il faut faire de la frame courante. */
    data class FramePlan(val runHandModel: Boolean)

    var mode: Mode = Mode.IDLE
        private set

    private var lastFrameAt = Long.MIN_VALUE / 2
    private var lastHandProbeAt = Long.MIN_VALUE / 2
    private var lastHandSeenAt = Long.MIN_VALUE / 2

    /**
     * À appeler pour chaque frame reçue de la caméra.
     * Retourne null si la frame doit être jetée (frame dropping temporel), sinon le plan
     * d'inférence (le modèle visage tourne sur toute frame retenue).
     */
    fun planFrame(nowMillis: Long): FramePlan? {
        val interval = if (mode == Mode.ACTIVE) activeIntervalMillis else idleIntervalMillis
        if (nowMillis - lastFrameAt < interval) return null
        lastFrameAt = nowMillis

        val runHands = when (mode) {
            Mode.ACTIVE -> true
            Mode.IDLE -> nowMillis - lastHandProbeAt >= handProbeIntervalMillis
        }
        if (runHands) lastHandProbeAt = nowMillis
        return FramePlan(runHandModel = runHands)
    }

    /** Résultat du modèle mains (uniquement pour les frames où il a tourné). */
    fun onHandsResult(handsPresent: Boolean, nowMillis: Long) {
        if (handsPresent) {
            lastHandSeenAt = nowMillis
            mode = Mode.ACTIVE
        } else if (mode == Mode.ACTIVE && nowMillis - lastHandSeenAt >= activeHoldMillis) {
            mode = Mode.IDLE
        }
    }
}
