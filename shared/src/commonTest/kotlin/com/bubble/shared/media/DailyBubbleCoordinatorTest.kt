package com.bubble.shared.media

import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.core.DailyPhase
import com.bubble.shared.crypto.PairingSession
import com.bubble.shared.purge.DailyBubbleStore
import com.bubble.shared.purge.PurgeEngine
import com.bubble.shared.core.DailyBubbleStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeFragmentSource(private val fragments: List<DailyFragment>) : DailyFragmentSource {
    override suspend fun fragmentsForToday() = fragments
}

private class FakeComposer(private val result: DailyBubbleResult) : DailyBubbleComposer {
    var calls = 0
    override suspend fun compile(fragments: List<DailyFragment>, outputPath: String, spec: DailyBubbleSpec): DailyBubbleResult {
        calls++
        return result
    }
}

private class FakeSecureStore : SecureDailyBubbleStore {
    var persisted = false
    var deleted = false
    override suspend fun persist(plainOutputPath: String): String {
        persisted = true
        return "$plainOutputPath.enc"
    }
    override suspend fun openForPlayback(): String? = if (persisted && !deleted) "playback.mp4" else null
    override suspend fun delete() {
        deleted = true
    }
    override fun exists(): Boolean = persisted && !deleted
}

/** Store vu par le PurgeEngine : reflète l'état daily bubble du manager pour l'exception. */
private class ManagerBackedBubbleStore(private val statusProvider: () -> DailyBubbleStatus) : DailyBubbleStore {
    var deleted = false
    override fun status() = statusProvider()
    override suspend fun delete() {
        deleted = true
    }
}

class DailyBubbleCoordinatorTest {
    private val session = PairingSession("couple", "fp-a", "fp-b")

    private fun coordinator(
        manager: BubbleStateManager,
        fragments: List<DailyFragment> = listOf(DailyFragment("/a.jpg", 1L), DailyFragment("/b.jpg", 2L)),
        composerResult: DailyBubbleResult = DailyBubbleResult.Success("/work/out.mp4", 15_000),
        secureStore: FakeSecureStore = FakeSecureStore(),
        bubbleStore: DailyBubbleStore = ManagerBackedBubbleStore { manager.dailyBubble.value },
    ): Pair<DailyBubbleCoordinator, FakeSecureStore> {
        val coord = DailyBubbleCoordinator(
            manager = manager,
            fragmentSource = FakeFragmentSource(fragments),
            composer = FakeComposer(composerResult),
            secureStore = secureStore,
            purgeEngine = PurgeEngine(emptyList(), bubbleStore),
            workingOutputPath = "/work/out.mp4",
        )
        return coord to secureStore
    }

    @Test
    fun generationCompilesEncryptsAndFlagsBubble() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(session)
        val (coord, store) = coordinator(manager)

        val result = coord.generateDailyBubble()

        assertTrue(result is DailyBubbleResult.Success)
        assertTrue(store.persisted, "le montage est chiffré au repos")
        assertTrue(manager.dailyBubble.value.exists)
        assertFalse(manager.dailyBubble.value.viewed)
        assertEquals(DailyPhase.IDLE, manager.dailyPhase.value)
    }

    @Test
    fun emptyDayProducesNoBubble() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(session)
        val (coord, store) = coordinator(manager, fragments = emptyList())

        assertEquals(DailyBubbleResult.Empty, coord.generateDailyBubble())
        assertFalse(store.persisted)
        assertFalse(manager.dailyBubble.value.exists)
        assertEquals(DailyPhase.IDLE, manager.dailyPhase.value)
    }

    @Test
    fun unwatchedBubbleSurvivesPurgeAndBecomesPersistedPhase() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(session)
        val bubbleStore = ManagerBackedBubbleStore { manager.dailyBubble.value }
        val (coord, store) = coordinator(manager, bubbleStore = bubbleStore)

        coord.generateDailyBubble() // bubble existe, non visionné
        coord.runPurge()

        assertFalse(bubbleStore.deleted, "un montage non visionné n'est JAMAIS supprimé")
        assertTrue(store.exists(), "le fichier chiffré est jalousement conservé")
        assertEquals(DailyPhase.PERSISTED_UNWATCHED, manager.dailyPhase.value)
    }

    @Test
    fun watchingPersistedBubbleDeletesItAndReturnsToBlankPage() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(session)
        val (coord, store) = coordinator(manager)

        coord.generateDailyBubble()
        coord.runPurge()
        assertEquals(DailyPhase.PERSISTED_UNWATCHED, manager.dailyPhase.value)

        coord.onDailyBubbleWatched()

        assertTrue(store.deleted, "le fichier est définitivement supprimé après visionnage")
        assertFalse(manager.dailyBubble.value.exists, "page blanche")
        assertEquals(DailyPhase.IDLE, manager.dailyPhase.value)
    }

    @Test
    fun watchedBubbleIsPurgedNormallyNoPersistence() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(session)
        val bubbleStore = ManagerBackedBubbleStore { manager.dailyBubble.value }
        val (coord, _) = coordinator(manager, bubbleStore = bubbleStore)

        coord.generateDailyBubble()
        manager.onDailyBubbleViewed() // vu avant 23h30
        coord.runPurge()

        assertTrue(bubbleStore.deleted, "un montage visionné est purgé normalement")
        assertEquals(DailyPhase.IDLE, manager.dailyPhase.value)
    }
}
