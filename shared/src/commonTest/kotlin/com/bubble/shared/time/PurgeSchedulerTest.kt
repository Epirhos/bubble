package com.bubble.shared.time

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class PurgeSchedulerTest {
    private val paris = TimeZone.of("UTC+2")
    private val montreal = TimeZone.of("UTC-4")

    @Test
    fun purgeFiresAtLatestTimezonesLocal2330() {
        // 18h00 UTC → 20h00 à Paris, 14h00 à Montréal.
        val now = Instant.parse("2026-07-19T18:00:00Z")
        val purge = PurgeScheduler.nextCouplePurgeInstant(now, paris, montreal)
        // 23h30 Montréal = 03h30 UTC le lendemain = 05h30 à Paris.
        assertEquals(Instant.parse("2026-07-20T03:30:00Z"), purge)
    }

    @Test
    fun nextOccurrenceRollsToTomorrowWhenPast2330() {
        // 22h00 UTC → 00h00 à Paris (UTC+2) : la 23h30 parisienne du jour est passée.
        val now = Instant.parse("2026-07-19T22:00:00Z")
        val next = PurgeScheduler.nextOccurrence(now, paris)
        assertEquals(Instant.parse("2026-07-20T21:30:00Z"), next)
    }

    @Test
    fun sameTimezoneCoupleGetsPlain2330() {
        val now = Instant.parse("2026-07-19T18:00:00Z")
        val purge = PurgeScheduler.nextCouplePurgeInstant(now, paris, paris)
        assertEquals(Instant.parse("2026-07-19T21:30:00Z"), purge)
    }

    @Test
    fun neitherPartnerIsPurgedBeforeTheirOwn2330() {
        val now = Instant.parse("2026-07-19T18:00:00Z")
        val purge = PurgeScheduler.nextCouplePurgeInstant(now, paris, montreal)
        val parisNext = PurgeScheduler.nextOccurrence(now, paris)
        val montrealNext = PurgeScheduler.nextOccurrence(now, montreal)
        check(purge >= parisNext && purge >= montrealNext)
    }
}
