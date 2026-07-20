package com.bubble.shared.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Calcule l'instant de la purge quotidienne (voir /brain/DATA_FLOW.md §5).
 *
 * Règle "fuseau le plus tardif" : la purge se déclenche à 23h30 dans le fuseau où il est
 * LE PLUS TÔT (le plus en retard sur l'horloge), donc à l'instant UTC le plus tardif des deux
 * occurrences — aucun des deux partenaires ne voit sa soirée coupée avant sa propre 23h30.
 * Ex. Paris (UTC+2) & Montréal (UTC−4) → purge à 23h30 Montréal = 05h30 à Paris.
 *
 * Recalculé quotidiennement et à chaque changement d'offset (voyage, DST) : les deux appareils
 * convergent vers le même instant UTC sans horloge serveur.
 */
object PurgeScheduler {
    val PURGE_LOCAL_TIME = LocalTime(hour = 23, minute = 30)

    /** Prochaine occurrence de 23h30 locale dans [zone], strictement après [now]. */
    fun nextOccurrence(now: Instant, zone: TimeZone): Instant {
        val today = now.toLocalDateTime(zone).date
        val todayCandidate = today.atTime(PURGE_LOCAL_TIME).toInstant(zone)
        return if (todayCandidate > now) {
            todayCandidate
        } else {
            today.plus(1, DateTimeUnit.DAY).atTime(PURGE_LOCAL_TIME).toInstant(zone)
        }
    }

    /** Instant de purge du couple : la plus tardive des deux prochaines 23h30 locales. */
    fun nextCouplePurgeInstant(now: Instant, selfZone: TimeZone, partnerZone: TimeZone): Instant =
        maxOf(nextOccurrence(now, selfZone), nextOccurrence(now, partnerZone))

    /**
     * Variante sur offsets UTC bruts (en minutes) — le format échangé dans le
     * [com.bubble.shared.signal.PeerMessage.Hello] de la poignée de main WebRTC.
     * Ex : 23h30 chez soi mais 22h30 chez l'autre → la purge attend la 23h30 de l'autre.
     */
    fun nextCouplePurgeInstant(now: Instant, selfOffsetMinutes: Int, partnerOffsetMinutes: Int): Instant =
        nextCouplePurgeInstant(
            now,
            UtcOffset(seconds = selfOffsetMinutes * 60).asTimeZone(),
            UtcOffset(seconds = partnerOffsetMinutes * 60).asTimeZone(),
        )
}
