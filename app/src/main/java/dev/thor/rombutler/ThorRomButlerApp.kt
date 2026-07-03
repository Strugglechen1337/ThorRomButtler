package dev.thor.rombutler

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.thor.rombutler.data.log.CrashLog
import javax.inject.Inject

/**
 * Application entry point. Annotated with [HiltAndroidApp] to trigger
 * Hilt's code generation and create the application-level DI container.
 * Implements [Configuration.Provider] so the watcher worker gets its
 * dependencies via Hilt.
 */
@HiltAndroidApp
class ThorRomButlerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var crashLog: CrashLog

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Local-only crash capture (shared manually via settings)
        crashLog.install()
    }
}
