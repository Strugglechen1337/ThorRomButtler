package dev.thor.rombutler.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thor.rombutler.data.log.CrashLog
import dev.thor.rombutler.domain.repository.SettingsRepository
import dev.thor.rombutler.receive.LocalNetworkPermission
import dev.thor.rombutler.receive.ReceiveManager
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Builds an explicitly shared support report without ROM names or session tokens. */
@Singleton
class DiagnosticReportBuilder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val receiveManager: ReceiveManager,
    private val crashLog: CrashLog,
) {
    suspend fun build(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val settings = settingsRepository.settings.first()
        val receive = receiveManager.diagnostics()
        val crash = crashLog.read()

        return buildString {
            appendLine("# Thor ROM Butler diagnostic report / Diagnosebericht")
            appendLine("Generated: ${Instant.now()}")
            appendLine()
            appendLine("## App")
            appendLine("Version: ${packageInfo.versionName ?: "?"} (${packageInfo.longVersionCode})")
            appendLine("Package: ${context.packageName}")
            appendLine()
            appendLine("## Device")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Locale: ${context.resources.configuration.locales[0].toLanguageTag()}")
            appendLine()
            appendLine("## Storage")
            appendLine("Manage all files: ${yesNo(Environment.isExternalStorageManager())}")
            appendLine("ROM base: ${folderStatus(settings.romBasePath)}")
            appendLine("Download folder: ${folderStatus(settings.downloadPath)}")
            appendLine("Additional sources: ${settings.additionalSourcePaths.size}")
            appendLine("DAT folder configured: ${yesNo(!settings.datFolderPath.isNullOrBlank())}")
            appendLine()
            appendLine("## LAN receive")
            appendLine("Permission: ${yesNo(LocalNetworkPermission.isGranted(context))}")
            appendLine("Network: ${receive.transport.name.lowercase(Locale.ROOT)}")
            appendLine("Local address: ${receive.localIp?.let { "$it:${receive.port}" } ?: "unavailable"}")
            appendLine("Download target ready: ${yesNo(receive.downloadFolderReady)}")
            appendLine("Server running: ${yesNo(receive.serverRunning)}")
            appendLine("Local server probe: ${receive.serverReachable?.let(::yesNo) ?: "not run"}")
            appendLine()
            appendLine("## Settings")
            appendLine("Background watcher: ${yesNo(settings.watcherEnabled)}")
            appendLine("Auto update check: ${yesNo(settings.autoUpdateCheck)}")
            appendLine("Archive trash: ${yesNo(settings.trashInsteadOfDelete)}")
            appendLine("Theme: ${settings.themeId}")
            appendLine("Custom systems configured: ${yesNo(!settings.customSystemPackJson.isNullOrBlank())}")
            appendLine()
            appendLine("ROM names, hashes and the protected LAN session token are intentionally omitted.")
            if (!crash.isNullOrBlank()) {
                appendLine()
                appendLine("## Last local crash log")
                appendLine("```")
                appendLine(crash)
                appendLine("```")
            }
        }
    }

    private fun folderStatus(path: String?): String {
        if (path.isNullOrBlank()) return "not configured"
        val folder = File(path)
        if (!folder.isDirectory) return "$path (missing)"
        return "$path (writable=${yesNo(folder.canWrite())}, free=${formatBytes(folder.usableSpace)})"
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= GIB -> String.format(Locale.ROOT, "%.1f GiB", bytes.toDouble() / GIB)
        bytes >= MIB -> String.format(Locale.ROOT, "%.0f MiB", bytes.toDouble() / MIB)
        else -> "$bytes B"
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val GIB = MIB * 1024
    }
}
