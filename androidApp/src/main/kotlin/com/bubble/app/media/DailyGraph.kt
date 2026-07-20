package com.bubble.app.media

import android.content.Context
import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.media.DailyBubbleCoordinator
import com.bubble.shared.media.DailyFragment
import com.bubble.shared.media.DailyFragmentSource
import com.bubble.shared.purge.PurgeEngine
import com.bubble.shared.purge.PurgeTarget
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Assemble le sous-système du cycle temporel (composer + stockage chiffré + purge) autour
 * du moteur. Instancié une fois par [com.bubble.app.BubbleApplication], partagé avec les workers.
 */
class DailyGraph(context: Context, val manager: BubbleStateManager) {

    private val appContext = context.applicationContext
    private val fragmentsDir = File(appContext.cacheDir, "daily_fragments")
    private val secureDir = File(appContext.filesDir, "daily_secure")
    private val workingOutput = File(appContext.cacheDir, "daily_working.mp4").absolutePath

    private val fragmentSource = object : DailyFragmentSource {
        override suspend fun fragmentsForToday(): List<DailyFragment> {
            val since = startOfTodayMillis()
            return (fragmentsDir.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.lastModified() >= since }
                .sortedBy { it.lastModified() }
                .map { DailyFragment(it.absolutePath, it.lastModified()) }
        }
    }

    val secureStore = KeystoreDailyBubbleStore(secureDir, statusProvider = { manager.dailyBubble.value })

    /** Cibles de purge : fragments du jour + montage de travail + caches widget. */
    private val purgeTargets: List<PurgeTarget> = listOf(
        fileTarget("daily-fragments", fragmentsDir),
        fileTarget("daily-working", File(workingOutput)),
    )

    val coordinator = DailyBubbleCoordinator(
        manager = manager,
        fragmentSource = fragmentSource,
        composer = MediaCodecDailyComposer(),
        secureStore = secureStore,
        purgeEngine = PurgeEngine(purgeTargets, secureStore),
        workingOutputPath = workingOutput,
    )

    fun selfUtcOffsetMinutes(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    private fun fileTarget(id: String, file: File): PurgeTarget = object : PurgeTarget {
        override val id = id
        override suspend fun purge() {
            if (file.isDirectory) file.listFiles()?.forEach { it.delete() } else file.delete()
        }
    }

    private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
