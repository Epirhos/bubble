package com.bubble.app.media

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bubble.app.BubbleApplication
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Worker de génération du Daily Bubble (21h locale). CoroutineWorker : l'encodage MediaCodec
 * tourne hors Main Thread, WorkManager garantit l'exécution même app fermée / après reboot.
 */
class DailyBubbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as BubbleApplication).dailyGraph ?: return Result.success()
        return try {
            graph.coordinator.generateDailyBubble()
            DailyBubbleScheduler.scheduleNextGeneration(applicationContext) // rearme demain 21h
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/**
 * Worker de purge (23h30 du fuseau le plus tardif). L'instant est recalculé à chaque
 * planification via `manager.nextPurgeInstant` (offset partenaire inclus).
 * Filet de rattrapage : si l'OS a différé la tâche, elle s'exécute au réveil — la purge
 * reste due tant qu'elle n'a pas eu lieu.
 */
class PurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as BubbleApplication).dailyGraph ?: return Result.success()
        return try {
            graph.coordinator.runPurge()
            DailyBubbleScheduler.scheduleNextPurge(applicationContext, graph.selfUtcOffsetMinutes())
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Planifie les deux rendez-vous quotidiens en tâche de fond. */
object DailyBubbleScheduler {
    private const val GENERATION_WORK = "bubble-daily-generation"
    private const val PURGE_WORK = "bubble-daily-purge"
    private const val GENERATION_HOUR = 21
    private const val GENERATION_MINUTE = 0

    fun scheduleAll(context: Context, selfUtcOffsetMinutes: Int) {
        scheduleNextGeneration(context)
        scheduleNextPurge(context, selfUtcOffsetMinutes)
    }

    fun scheduleNextGeneration(context: Context) {
        val delay = millisUntilLocal(GENERATION_HOUR, GENERATION_MINUTE)
        val request = OneTimeWorkRequestBuilder<DailyBubbleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GENERATION_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleNextPurge(context: Context, selfUtcOffsetMinutes: Int) {
        val graph = (context.applicationContext as BubbleApplication).dailyGraph
        val purgeInstant = graph?.manager?.nextPurgeInstant(selfUtcOffsetMinutes)
        val delay = purgeInstant
            ?.let { it.toEpochMilliseconds() - System.currentTimeMillis() }
            ?.coerceAtLeast(0L)
            ?: millisUntilLocal(23, 30)
        val request = OneTimeWorkRequestBuilder<PurgeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PURGE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun millisUntilLocal(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
