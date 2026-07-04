package com.spamshield

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * SpamRefreshWorker — the self-starting periodic refresh for the threat feeds + crowd feed.
 *
 * WHAT / WHY
 * ----------
 * Downloading the feeds must happen on a schedule with ZERO manual action after a reboot (hard
 * project rule). WorkManager persists the job across reboots and app kills and re-runs it on its
 * own, battery- and network-aware — so once [schedule] is called once, the feeds stay fresh
 * forever with no user step. On failure the engine keeps the previously cached feed (never wiped).
 *
 * HOW IT'S CALLED: `SpamShield.scheduleAutoRefresh(context)` → [schedule] enqueues a unique
 * periodic work; the system later constructs this worker and calls [doWork], which just delegates
 * to `SpamShield.refreshNow()`.
 *
 * STATUS: compile-UNVERIFIED here (no Android/Gradle env). Uses only androidx.work — standard.
 */
class SpamRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (SpamShield.refreshNow()) Result.success() else Result.retry()

    companion object {
        private const val UNIQUE_NAME = "com.spamshield.refresh"

        /**
         * Enqueue the periodic refresh (once per ~12h, only when the network is available).
         * Idempotent: KEEP policy means calling it again won't duplicate or reset the schedule.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SpamRefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
