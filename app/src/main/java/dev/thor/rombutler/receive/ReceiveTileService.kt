package dev.thor.rombutler.receive

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.thor.rombutler.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile for LAN receive. Tapping opens a compact session dialog
 * because some SystemUI variants hide tile subtitles. The active label also
 * includes the short random session code.
 *
 * Starting the foreground service from here is permitted: a Quick Settings
 * click counts as a user interaction, which exempts the app from
 * background-start restrictions.
 */
@AndroidEntryPoint
class ReceiveTileService : TileService() {

    @Inject
    lateinit var manager: ReceiveManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob = serviceScope.launch {
            manager.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        openReceiveSession()
    }

    private fun render(state: ReceiveState) {
        val tile = qsTile ?: return
        when (state) {
            is ReceiveState.Running -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.receive_tile_active, sessionCode(state.url))
                tile.subtitle = state.url.removePrefix("http://")
            }

            ReceiveState.Off -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.receive_title)
                tile.subtitle = null
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openReceiveSession() {
        val intent = Intent(this, ReceivePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // The PendingIntent overload does not exist on the minimum API 33.
            startActivityAndCollapse(intent)
        }
    }

    private fun sessionCode(url: String): String = url.trimEnd('/').substringAfterLast('/')
}
