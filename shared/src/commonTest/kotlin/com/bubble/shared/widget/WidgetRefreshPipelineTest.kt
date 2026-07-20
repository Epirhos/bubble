package com.bubble.shared.widget

import com.bubble.shared.core.AuraGlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class FakeBridge : WidgetBridge {
    val snapshots = mutableListOf<Int>() // taille des octets écrits, pour identifier
    val auras = mutableListOf<Float>()

    override suspend fun writeSnapshot(bytes: ByteArray, receivedAtMillis: Long) {
        snapshots += bytes.size
    }

    override suspend fun writeAura(intensity: Float) {
        auras += intensity
    }
}

/** Horloge fixe : l'intensité d'Aura est déterministe dans les tests. */
private class FixedClock(private val instant: Instant) : Clock {
    override fun now() = instant
}

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRefreshPipelineTest {

    @Test
    fun latestSnapshotWinsAndWritesAreRateLimited() = runTest {
        val snapshots = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        val aura = MutableStateFlow(AuraGlow.NEUTRAL)
        val bridge = FakeBridge()
        WidgetRefreshPipeline(
            backgroundScope, snapshots, aura, bridge,
            clock = FixedClock(Instant.fromEpochMilliseconds(0)),
            snapshotMinInterval = 30.seconds,
        ).start()
        runCurrent()

        // Rafale : le premier passe (leading), les suivants sont conflatés.
        snapshots.emit(ByteArray(1))
        runCurrent()
        assertEquals(listOf(1), bridge.snapshots)

        snapshots.emit(ByteArray(2))
        snapshots.emit(ByteArray(3)) // seul le dernier (taille 3) survit dans le canal conflated
        advanceTimeBy(31.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals(listOf(1, 3), bridge.snapshots, "un seul par fenêtre, le plus récent")
    }

    @Test
    fun auraIntensityReflectsPresenceAndIsThrottled() = runTest {
        val snapshots = MutableSharedFlow<ByteArray>()
        val now = Instant.fromEpochMilliseconds(1_000_000)
        val aura = MutableStateFlow(AuraGlow.NEUTRAL)
        val bridge = FakeBridge()
        WidgetRefreshPipeline(
            backgroundScope, snapshots, aura, bridge,
            clock = FixedClock(now),
            auraMinInterval = 60.seconds,
        ).start()
        runCurrent()

        assertEquals(listOf(0f), bridge.auras, "aura neutre au démarrage")

        aura.value = AuraGlow(lastPresenceAt = now) // présence à l'instant courant → intensité 1
        advanceTimeBy(61.seconds.inWholeMilliseconds)
        runCurrent()
        assertTrue(bridge.auras.last() > 0.9f, "le partenaire actif allume l'Aura")
    }
}
