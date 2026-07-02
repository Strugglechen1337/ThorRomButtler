package dev.thor.rombutler

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Annotated with [HiltAndroidApp] to trigger
 * Hilt's code generation and create the application-level DI container.
 */
@HiltAndroidApp
class ThorRomButlerApp : Application()
