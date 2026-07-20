package com.bubble.shared.haptics

/**
 * Moteur haptique natif (VibrationEffect sur Android, CoreHaptics sur iOS).
 * API sémantique : le moteur ne connaît que des sensations, jamais des durées brutes —
 * les patterns sont définis UNE SEULE FOIS dans [HapticScores] (commonMain) et traduits
 * par chaque plateforme, pour que le toucher soit identique des deux côtés.
 */
interface HapticEngine {
    /** Bisou : double tape douce, légère puis un peu plus appuyée. */
    fun playKiss()

    /** Cœur : "lub-dub" organique, battement fort puis écho plus doux. */
    fun playHeartbeat()

    /** Pulsation d'Aura : nappe longue et très discrète (présence, jamais alarme). */
    fun playAuraPulse()

    /**
     * Battement bref du Radar Haptique, d'intensité paramétrable (0..1). Appelé à cadence
     * contrôlée par [com.bubble.shared.play.HapticRadarEngine], jamais par frame UI.
     */
    fun playRadarPulse(intensity: Float)
}

/**
 * Une impulsion d'une partition haptique.
 * [intensity] et [sharpness] sont normalisées 0..1 (vocabulaire CoreHaptics, traduit en
 * amplitude 1..255 côté Android). [sharpness] : 0 = rond/sourd, 1 = sec/net.
 */
data class HapticPulse(
    val atMillis: Long,
    val durationMillis: Long,
    val intensity: Float,
    val sharpness: Float,
)

/** Partition complète d'une sensation : liste d'impulsions sur une timeline commune. */
data class HapticScore(val pulses: List<HapticPulse>) {
    val totalDurationMillis: Long =
        pulses.maxOfOrNull { it.atMillis + it.durationMillis } ?: 0L
}

/**
 * Source unique de vérité des sensations Bubble.
 * Les timings (la "partition") sont IDENTIQUES sur iOS et Android ; seule la traduction
 * intensité→matériel diffère (amplitude vs CHHapticEventParameter).
 */
object HapticScores {
    /** Deux tapes douces : 45 ms à 45 %, respiration de 70 ms, 45 ms à 65 %. */
    val KISS = HapticScore(
        listOf(
            HapticPulse(atMillis = 0, durationMillis = 45, intensity = 0.45f, sharpness = 0.30f),
            HapticPulse(atMillis = 115, durationMillis = 45, intensity = 0.65f, sharpness = 0.35f),
        ),
    )

    /** Lub-dub : battement fort et bref, silence de 180 ms, écho plus long et plus doux. */
    val HEARTBEAT = HapticScore(
        listOf(
            HapticPulse(atMillis = 0, durationMillis = 55, intensity = 0.90f, sharpness = 0.50f),
            HapticPulse(atMillis = 235, durationMillis = 70, intensity = 0.60f, sharpness = 0.35f),
        ),
    )

    /** Nappe continue de 400 ms à 25 % : perceptible en main, invisible sur une table. */
    val AURA_PULSE = HapticScore(
        listOf(
            HapticPulse(atMillis = 0, durationMillis = 400, intensity = 0.25f, sharpness = 0.10f),
        ),
    )
}
