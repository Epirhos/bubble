package com.bubble.app.play

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bubble.app.BubbleApplication
import com.bubble.shared.play.PromptRepository
import java.util.concurrent.TimeUnit

/**
 * "La Roulette Asynchrone" : une fois par semaine, pioche un coupon-cadeau et l'arme
 * localement. Le widget affiche alors une icône cadeau ; au tap, l'app le révèle puis il
 * s'évapore (onCouponRead). 100 % local, aucun serveur.
 */
class WeeklyCouponWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? BubbleApplication ?: return Result.success()
        val store = FilePromptStore(applicationContext)
        val coupon = PromptRepository(store).randomCoupon()
        app.armWeeklyCoupon(coupon)
        return Result.success()
    }

    companion object {
        private const val WORK = "bubble-weekly-coupon"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklyCouponWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(7, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
