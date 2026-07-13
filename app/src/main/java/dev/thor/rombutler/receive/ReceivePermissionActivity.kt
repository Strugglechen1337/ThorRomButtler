package dev.thor.rombutler.receive

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.thor.rombutler.R
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Starts LAN receive from the Quick Settings tile and displays its session URL. */
@AndroidEntryPoint
class ReceivePermissionActivity : ComponentActivity() {

    @Inject
    lateinit var receiveManager: ReceiveManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startReceive()
        } else {
            Toast.makeText(this, R.string.receive_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val state = receiveManager.state.value) {
            is ReceiveState.Running -> showSessionAddress(state.url)
            ReceiveState.Off -> {
                if (LocalNetworkPermission.isGranted(this)) {
                    startReceive()
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
        }
    }

    private fun startReceive() {
        lifecycleScope.launch {
            val started = receiveManager.start()
            val running = receiveManager.state.value as? ReceiveState.Running
            if (!started || running == null) {
                Toast.makeText(
                    this@ReceivePermissionActivity,
                    R.string.receive_failed,
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            } else {
                showSessionAddress(running.url)
            }
        }
    }

    private fun showSessionAddress(url: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.receive_title)
            .setMessage(getString(R.string.receive_tile_address, url))
            .setPositiveButton(R.string.receive_copy_address) { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.receive_title), url))
                Toast.makeText(this, R.string.receive_address_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.receive_stop) { _, _ -> receiveManager.stop() }
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }
}
