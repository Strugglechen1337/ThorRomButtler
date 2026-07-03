package dev.thor.rombutler.watcher

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules/cancels the periodic [WatcherWorker] (30-minute interval,
 * WorkManager minimum is 15). Toggled from the settings screen.
 */
@Singleton
class WatcherScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun setEnabled(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (enabled) {
            workManager.enqueueUniquePeriodicWork(
                WatcherWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatcherWorker>(30, TimeUnit.MINUTES).build(),
            )
        } else {
            workManager.cancelUniqueWork(WatcherWorker.WORK_NAME)
        }
    }
}
