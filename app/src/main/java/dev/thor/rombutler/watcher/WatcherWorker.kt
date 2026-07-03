package dev.thor.rombutler.watcher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.thor.rombutler.MainActivity
import dev.thor.rombutler.R
import dev.thor.rombutler.domain.repository.ArchiveRepository
import dev.thor.rombutler.domain.repository.LooseRomRepository
import kotlinx.coroutines.flow.first

/**
 * Watcher mode: periodically checks the download folder in the background
 * and posts a notification when NEW ROM candidates appeared since the last
 * run. It never sorts anything by itself — tapping the notification opens
 * the app, the user reviews as always.
 */
@HiltWorker
class WatcherWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val archiveRepository: ArchiveRepository,
    private val looseRomRepository: LooseRomRepository,
    private val dataStore: DataStore<Preferences>,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val current = buildSet {
            archiveRepository.scanForArchives().forEach { add(it.path) }
            looseRomRepository.scanAndDetect().forEach { addAll(it.memberEntryPaths) }
        }

        val seen = dataStore.data.first()[SEEN_PATHS] ?: emptySet()
        val newCount = (current - seen).size
        dataStore.edit { it[SEEN_PATHS] = current }

        if (newCount > 0) {
            notifyNewFinds(newCount)
        }
        return Result.success()
    }

    private fun notifyNewFinds(count: Int) {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_watcher),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bolt)
            .setContentTitle(context.getString(R.string.notification_watcher_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.notification_watcher_text,
                    count,
                    count,
                ),
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val WORK_NAME = "download_watcher"
        private const val CHANNEL_ID = "watcher"
        private const val NOTIFICATION_ID = 2
        private val SEEN_PATHS = stringSetPreferencesKey("watcher_seen_paths")
    }
}
