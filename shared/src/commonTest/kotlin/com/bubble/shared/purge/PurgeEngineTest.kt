package com.bubble.shared.purge

import com.bubble.shared.core.DailyBubbleStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeTarget(override val id: String, private val failing: Boolean = false) : PurgeTarget {
    var purgedCount = 0

    override suspend fun purge() {
        if (failing) error("boom")
        purgedCount++
    }
}

private class FakeDailyBubbleStore(private var status: DailyBubbleStatus) : DailyBubbleStore {
    var deleted = false

    override fun status(): DailyBubbleStatus = status

    override suspend fun delete() {
        deleted = true
        status = DailyBubbleStatus.NONE
    }
}

class PurgeEngineTest {
    @Test
    fun unviewedDailyBubbleIsNeverDeleted() = runTest {
        val store = FakeDailyBubbleStore(DailyBubbleStatus(exists = true, viewed = false))
        val report = PurgeEngine(emptyList(), store).execute()

        assertFalse(store.deleted)
        assertTrue(report.dailyBubbleProtected)
        assertFalse(report.dailyBubblePurged)
    }

    @Test
    fun viewedDailyBubbleIsDeleted() = runTest {
        val store = FakeDailyBubbleStore(DailyBubbleStatus(exists = true, viewed = true))
        val report = PurgeEngine(emptyList(), store).execute()

        assertTrue(store.deleted)
        assertTrue(report.dailyBubblePurged)
        assertFalse(report.dailyBubbleProtected)
    }

    @Test
    fun failingTargetDoesNotStopOthers() = runTest {
        val ok = FakeTarget("snapshots")
        val ko = FakeTarget("relay", failing = true)
        val ok2 = FakeTarget("echo-queue")
        val store = FakeDailyBubbleStore(DailyBubbleStatus.NONE)

        val report = PurgeEngine(listOf(ok, ko, ok2), store).execute()

        assertEquals(listOf("snapshots", "echo-queue"), report.purgedTargetIds)
        assertEquals(listOf("relay"), report.failedTargetIds)
        assertEquals(1, ok2.purgedCount)
    }
}
