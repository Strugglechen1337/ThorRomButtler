package dev.thor.rombutler.receive

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.AndroidEntryPoint
import dev.thor.rombutler.R
import dev.thor.rombutler.domain.model.AppSettings
import dev.thor.rombutler.domain.repository.SettingsRepository
import dev.thor.rombutler.ui.components.formatFileSize
import dev.thor.rombutler.ui.theme.ThorRomButlerTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Starts LAN receive from the Quick Settings tile and shows the active session. */
@AndroidEntryPoint
class ReceivePermissionActivity : ComponentActivity() {

    @Inject
    lateinit var receiveManager: ReceiveManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var loading by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    private var diagnostics by mutableStateOf<ReceiveDiagnostics?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startReceive()
        } else {
            loading = false
            failed = true
            Toast.makeText(this, R.string.receive_permission_denied, Toast.LENGTH_LONG).show()
            refreshDiagnostics()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val receiveState by receiveManager.state.collectAsStateWithLifecycle()
            ThorRomButlerTheme(settings.themeId) {
                ReceiveSessionScreen(
                    receiveState = receiveState,
                    diagnostics = diagnostics,
                    loading = loading,
                    failed = failed,
                    onCopy = ::copyAddress,
                    onShare = ::shareAddress,
                    onRefresh = ::refreshDiagnostics,
                    onRetry = ::startOrRequestPermission,
                    onStop = {
                        receiveManager.stop()
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
        startOrRequestPermission()
    }

    private fun startOrRequestPermission() {
        loading = true
        failed = false
        if (receiveManager.state.value is ReceiveState.Running) {
            loading = false
            refreshDiagnostics()
        } else if (LocalNetworkPermission.isGranted(this)) {
            startReceive()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    private fun startReceive() {
        loading = true
        lifecycleScope.launch {
            val started = receiveManager.start()
            loading = false
            failed = !started
            if (!started) {
                Toast.makeText(
                    this@ReceivePermissionActivity,
                    R.string.receive_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
            refreshDiagnostics()
        }
    }

    private fun refreshDiagnostics() {
        lifecycleScope.launch {
            diagnostics = receiveManager.diagnostics()
        }
    }

    private fun copyAddress(url: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.receive_title), url))
        Toast.makeText(this, R.string.receive_address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAddress(url: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.receive_title))
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(share, getString(R.string.receive_share_address)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveSessionScreen(
    receiveState: ReceiveState,
    diagnostics: ReceiveDiagnostics?,
    loading: Boolean,
    failed: Boolean,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receive_title)) },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val running = receiveState as? ReceiveState.Running
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                running != null -> ActiveSession(
                    state = running,
                    onCopy = onCopy,
                    onShare = onShare,
                )

                failed -> {
                    Text(
                        text = stringResource(R.string.receive_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.receive_retry))
                    }
                }
            }

            if (!loading) {
                Spacer(Modifier.height(24.dp))
                DiagnosticsSection(diagnostics = diagnostics, onRefresh = onRefresh)
                Spacer(Modifier.height(24.dp))
                if (running != null) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.receive_stop))
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ActiveSession(
    state: ReceiveState.Running,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.receive_running_hint),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Surface(
        modifier = Modifier.sizeIn(maxWidth = 280.dp).fillMaxWidth().aspectRatio(1f),
        color = androidx.compose.ui.graphics.Color.White,
        shape = RoundedCornerShape(8.dp),
    ) {
        val qrCode = remember(state.url) { createQrCode(state.url, 640) }
        Image(
            bitmap = qrCode,
            contentDescription = stringResource(R.string.receive_qr_description),
            modifier = Modifier.fillMaxSize().padding(12.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    SelectionContainer {
        Text(
            text = state.url,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(16.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { onCopy(state.url) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.receive_copy_address))
        }
        OutlinedButton(onClick = { onShare(state.url) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.receive_share_address))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.receive_count, state.receivedCount),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiagnosticsSection(
    diagnostics: ReceiveDiagnostics?,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.receive_diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.receive_diagnostics_refresh),
            )
        }
    }
    HorizontalDivider()
    if (diagnostics == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(88.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        return
    }

    DiagnosticRow(
        label = stringResource(R.string.receive_diagnostics_permission),
        value = if (diagnostics.permissionGranted) {
            stringResource(R.string.receive_diagnostics_ok)
        } else {
            stringResource(R.string.receive_diagnostics_missing)
        },
        ok = diagnostics.permissionGranted,
    )
    DiagnosticRow(
        label = stringResource(R.string.receive_diagnostics_network),
        value = when (diagnostics.transport) {
            ReceiveTransport.WIFI -> stringResource(R.string.receive_diagnostics_wifi)
            ReceiveTransport.ETHERNET -> stringResource(R.string.receive_diagnostics_ethernet)
            ReceiveTransport.OTHER -> stringResource(R.string.receive_diagnostics_other_network)
            ReceiveTransport.NONE -> stringResource(R.string.receive_diagnostics_no_network)
        },
        ok = diagnostics.networkConnected,
    )
    DiagnosticRow(
        label = stringResource(R.string.receive_diagnostics_address),
        value = diagnostics.localIp?.let { "$it:${diagnostics.port}" }
            ?: stringResource(R.string.receive_diagnostics_missing),
        ok = diagnostics.localIp != null,
    )
    DiagnosticRow(
        label = stringResource(R.string.receive_diagnostics_folder),
        value = diagnostics.freeBytes?.let {
            stringResource(R.string.receive_diagnostics_free, formatFileSize(it))
        } ?: stringResource(R.string.receive_diagnostics_missing),
        ok = diagnostics.downloadFolderReady,
    )
    DiagnosticRow(
        label = stringResource(R.string.receive_diagnostics_server),
        value = if (diagnostics.serverReachable == true) {
            stringResource(R.string.receive_diagnostics_reachable)
        } else {
            stringResource(R.string.receive_diagnostics_unreachable)
        },
        ok = diagnostics.serverReachable == true,
    )
}

@Composable
private fun DiagnosticRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun createQrCode(value: String, size: Int) = QRCodeWriter()
    .encode(
        value,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        ),
    )
    .let { matrix ->
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) android.graphics.Color.BLACK
            else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }.asImageBitmap()
    }
