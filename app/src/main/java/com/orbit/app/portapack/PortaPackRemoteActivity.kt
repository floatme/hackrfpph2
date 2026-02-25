package com.orbit.app.portapack

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.orbit.app.BuildConfig
import com.orbit.app.R
import com.orbit.app.ui.theme.OrbitTheme
import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.roundToInt

class PortaPackRemoteActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DEMO_CONNECTED = "com.orbit.app.portapack.extra.DEMO_CONNECTED"
    }

    private val permissionAction = "com.orbit.app.portapack.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private lateinit var permissionIntent: PendingIntent
    private lateinit var controller: PortaPackRemoteController

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    if (!intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)) {
                        return
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device == null) {
                        return
                    }
                    if (granted) {
                        controller.refreshDevices()
                        controller.setSelectedDevice(device.deviceId)
                        controller.connectSelected(::requestUsbPermission)
                    } else {
                        controller.disconnect("USB permission denied")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    controller.refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    controller.onUsbDetached(detached?.deviceId ?: -1)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(permissionAction).setPackage(packageName),
            flags
        )

        controller = PortaPackRemoteController(usbManager)
        controller.refreshDevices()
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO_CONNECTED, false)) {
            controller.enableDemoConnectedState()
        }

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            OrbitTheme {
                val state by controller.state.collectAsState()
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                LaunchedEffect(state.fullscreenMode) {
                    WindowCompat.setDecorFitsSystemWindows(window, !state.fullscreenMode)
                    if (state.fullscreenMode) {
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                        insetsController.systemBarsBehavior =
                            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
                DisposableEffect(Unit) {
                    onDispose {
                        WindowCompat.setDecorFitsSystemWindows(window, true)
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
                PortaPackRemoteScreen(
                    state = state,
                    onRefreshDevices = controller::refreshDevices,
                    onConnect = { controller.connectSelected(::requestUsbPermission) },
                    onDisconnect = controller::disconnect,
                    onSelectDevice = controller::setSelectedDevice,
                    onSelectScreenProfile = controller::setScreenProfile,
                    onFullscreenModeChange = controller::setFullscreenMode,
                    onFullscreenControlsVisibleChange = controller::setFullscreenControlsVisible,
                    onTouchLockChange = controller::setTouchLock,
                    onToggleFineMode = controller::toggleFineMode,
                    onUp = controller::sendUp,
                    onDown = controller::sendDown,
                    onLeft = controller::sendLeft,
                    onRight = controller::sendRight,
                    onOk = controller::sendOk,
                    onEncoderDragStart = controller::onEncoderDragStart,
                    onEncoderDrag = controller::onEncoderDrag,
                    onEncoderDragEnd = controller::onEncoderDragEnd,
                    onEncoderDialStart = controller::onEncoderDialStart,
                    onEncoderDialRotate = controller::onEncoderDialRotate,
                    onEncoderDialEnd = controller::onEncoderDialEnd,
                    onFrameTouch = controller::sendTouch,
                    onSendKeyboardText = controller::sendKeyboardText,
                    onExitRequested = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        controller.shutdown()
    }

    private fun requestUsbPermission(device: UsbDevice) {
        try {
            usbManager.requestPermission(device, permissionIntent)
        } catch (_: Exception) {
            controller.disconnect("Failed to request USB permission")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortaPackRemoteScreen(
    state: PortaPackRemoteUiState,
    onRefreshDevices: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectDevice: (Int) -> Unit,
    onSelectScreenProfile: (ScreenProfile) -> Unit,
    onFullscreenModeChange: (Boolean) -> Unit,
    onFullscreenControlsVisibleChange: (Boolean) -> Unit,
    onTouchLockChange: (Boolean) -> Unit,
    onToggleFineMode: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
    onEncoderDragStart: () -> Unit,
    onEncoderDrag: (Float) -> Unit,
    onEncoderDragEnd: () -> Unit,
    onEncoderDialStart: () -> Unit,
    onEncoderDialRotate: (Float) -> Unit,
    onEncoderDialEnd: () -> Unit,
    onFrameTouch: (Int, Int) -> Unit,
    onSendKeyboardText: (String, Boolean) -> Unit,
    onExitRequested: () -> Unit
) {
    var keyboardDialogVisible by remember { mutableStateOf(false) }
    var startupDonationVisible by rememberSaveable { mutableStateOf(true) }
    var exitDonationVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = !startupDonationVisible && !exitDonationVisible) {
        exitDonationVisible = true
    }

    if (startupDonationVisible) {
        DonationDialog(
            title = "Buy Me A Coffee",
            showCloseAction = false,
            onDismiss = { startupDonationVisible = false },
            onCloseApp = onExitRequested
        )
    }

    if (exitDonationVisible) {
        DonationDialog(
            title = "Before You Go",
            showCloseAction = true,
            onDismiss = { exitDonationVisible = false },
            onCloseApp = onExitRequested
        )
    }

    if (keyboardDialogVisible) {
        FrequencyKeyboardDialog(
            onDismiss = { keyboardDialogVisible = false },
            onType = { value, applyAfter ->
                onSendKeyboardText(value, applyAfter)
                keyboardDialogVisible = false
            }
        )
    }

    if (state.fullscreenMode) {
        FullscreenMirror(
            state = state,
            onExitFullscreen = { onFullscreenModeChange(false) },
            onFullscreenControlsVisibleChange = onFullscreenControlsVisibleChange,
            onOpenKeyboardDialog = { keyboardDialogVisible = true },
            onUp = onUp,
            onDown = onDown,
            onLeft = onLeft,
            onRight = onRight,
            onOk = onOk,
            onEncoderDialStart = onEncoderDialStart,
            onEncoderDialRotate = onEncoderDialRotate,
            onEncoderDialEnd = onEncoderDialEnd
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(id = R.string.portapack_remote_title)) },
                actions = {
                    IconButton(onClick = onRefreshDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
                    }
                    IconButton(
                        onClick = {
                            if (state.connected) onDisconnect() else onConnect()
                        }
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connect/Disconnect")
                    }
                    IconButton(
                        onClick = { onFullscreenModeChange(true) }
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionPanel(
                state = state,
                onSelectDevice = onSelectDevice,
                onSelectScreenProfile = onSelectScreenProfile,
                onFullscreenModeChange = onFullscreenModeChange,
                onFullscreenControlsVisibleChange = onFullscreenControlsVisibleChange,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onTouchLockChange = onTouchLockChange
            )
            FramePanel(state = state, onFrameTouch = onFrameTouch)
            ControlsPanel(
                state = state,
                onToggleFineMode = onToggleFineMode,
                onUp = onUp,
                onDown = onDown,
                onLeft = onLeft,
                onRight = onRight,
                onOk = onOk,
                onEncoderDragStart = onEncoderDragStart,
                onEncoderDrag = onEncoderDrag,
                onEncoderDragEnd = onEncoderDragEnd,
                onEncoderDialStart = onEncoderDialStart,
                onEncoderDialRotate = onEncoderDialRotate,
                onEncoderDialEnd = onEncoderDialEnd,
                onTouchLockChange = onTouchLockChange,
                onOpenKeyboardDialog = { keyboardDialogVisible = true }
            )
        }
    }
}

@Composable
private fun ConnectionPanel(
    state: PortaPackRemoteUiState,
    onSelectDevice: (Int) -> Unit,
    onSelectScreenProfile: (ScreenProfile) -> Unit,
    onFullscreenModeChange: (Boolean) -> Unit,
    onFullscreenControlsVisibleChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTouchLockChange: (Boolean) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (state.connected) "Connected" else "Not connected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Detected: ${state.detectedScreenSize.width}x${state.detectedScreenSize.height}  Active: ${state.screenSize.width}x${state.screenSize.height}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Link mode: ${if (state.turboLinkActive) "Turbo" else "Legacy"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Last control: ${state.lastControl}",
                style = MaterialTheme.typography.bodySmall
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ScreenProfile.entries, key = { it.name }) { profile ->
                    FilterChip(
                        selected = profile == state.screenProfile,
                        onClick = { onSelectScreenProfile(profile) },
                        label = { Text(profile.label) }
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.availableDevices, key = { it.deviceId }) { device ->
                    FilterChip(
                        selected = device.deviceId == state.selectedDeviceId,
                        onClick = { onSelectDevice(device.deviceId) },
                        label = {
                            Text("${device.title} ${if (device.hasPermission) "granted" else "needs permission"}")
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onConnect,
                    enabled = !state.connected && state.availableDevices.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connect")
                }
                FilledTonalButton(
                    onClick = onDisconnect,
                    enabled = state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
                FilledTonalButton(
                    onClick = { onFullscreenModeChange(true) },
                    enabled = state.connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Full")
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Touch Lock", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.touchLock,
                        onCheckedChange = onTouchLockChange
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("FS Controls", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.fullscreenControlsVisible,
                        onCheckedChange = onFullscreenControlsVisibleChange
                    )
                }
            }
        }
    }
}

@Composable
private fun FramePanel(
    state: PortaPackRemoteUiState,
    onFrameTouch: (Int, Int) -> Unit
) {
    val screenSize = state.screenSize
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(screenSize.width.toFloat() / screenSize.height.toFloat())
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = state.frameBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PortaPack mirrored screen",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(state.connected, state.touchLock, screenSize.width, screenSize.height) {
                            if (!state.connected || state.touchLock) return@pointerInput
                            detectTapGestures { offset ->
                                val viewWidthPx = size.width.toFloat().coerceAtLeast(1f)
                                val viewHeightPx = size.height.toFloat().coerceAtLeast(1f)
                                val mappedX = ((offset.x / viewWidthPx) * screenSize.width).roundToInt()
                                val mappedY = ((offset.y / viewHeightPx) * screenSize.height).roundToInt()
                                onFrameTouch(mappedX, mappedY)
                            }
                        }
                )
            } else {
                Text(
                    text = "No frame yet.\nConnect USB and wait for stream.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun FullscreenMirror(
    state: PortaPackRemoteUiState,
    onExitFullscreen: () -> Unit,
    onFullscreenControlsVisibleChange: (Boolean) -> Unit,
    onOpenKeyboardDialog: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
    onEncoderDialStart: () -> Unit,
    onEncoderDialRotate: (Float) -> Unit,
    onEncoderDialEnd: () -> Unit
) {
    val controlsEnabled = state.connected && !state.touchLock

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val bitmap = state.frameBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PortaPack mirrored screen fullscreen",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No frame",
                    color = Color.White
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onOpenKeyboardDialog,
                enabled = controlsEnabled
            ) {
                Text("Freq KB")
            }
            FilledTonalButton(
                onClick = {
                    onFullscreenControlsVisibleChange(!state.fullscreenControlsVisible)
                }
            ) {
                Text(if (state.fullscreenControlsVisible) "Hide UI" else "Show UI")
            }
            FilledTonalButton(onClick = onExitFullscreen) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Exit")
            }
        }

        if (state.fullscreenControlsVisible) {
            ElevatedCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!state.connected) {
                        Text("Connect USB to enable controls", style = MaterialTheme.typography.bodySmall)
                    } else if (state.touchLock) {
                        Text("Touch Lock is enabled", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onLeft,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("LT") }
                        FilledTonalButton(
                            onClick = onUp,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("UP") }
                        FilledTonalButton(
                            onClick = onDown,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("DN") }
                        FilledTonalButton(
                            onClick = onRight,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("RT") }
                        FilledTonalButton(
                            onClick = onOk,
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("OK") }
                    }

                    RotaryDial(
                        enabled = controlsEnabled,
                        fineMode = state.fineMode,
                        onDialStart = onEncoderDialStart,
                        onDialRotate = onEncoderDialRotate,
                        onDialEnd = onEncoderDialEnd,
                        onPress = onOk,
                        compact = true
                    )
                }
            }
        } else {
            FilledTonalButton(
                onClick = { onFullscreenControlsVisibleChange(true) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            ) {
                Text("Controls")
            }
        }
    }
}

@Composable
private fun ControlsPanel(
    state: PortaPackRemoteUiState,
    onToggleFineMode: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
    onEncoderDragStart: () -> Unit,
    onEncoderDrag: (Float) -> Unit,
    onEncoderDragEnd: () -> Unit,
    onEncoderDialStart: () -> Unit,
    onEncoderDialRotate: (Float) -> Unit,
    onEncoderDialEnd: () -> Unit,
    onTouchLockChange: (Boolean) -> Unit,
    onOpenKeyboardDialog: () -> Unit
) {
    val controlsEnabled = state.connected && !state.touchLock

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Virtual Hardware Controls",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = onToggleFineMode,
                    enabled = controlsEnabled
                ) {
                    Text(if (state.fineMode) "Fine" else "Coarse")
                }
                FilledTonalButton(
                    onClick = onOpenKeyboardDialog,
                    enabled = controlsEnabled
                ) {
                    Text("Freq KB")
                }
            }

            if (!state.connected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFE6EEF8)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Connect USB to enable controls", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.touchLock) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFE2D6)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Touch Lock enabled", fontWeight = FontWeight.Bold)
                        Text("Use switch above to unlock controls", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DPad(
                    modifier = Modifier.weight(1f),
                    enabled = controlsEnabled,
                    onUp = onUp,
                    onDown = onDown,
                    onLeft = onLeft,
                    onRight = onRight,
                    onOk = onOk
                )

                EncoderStrip(
                    modifier = Modifier.weight(1f),
                    enabled = controlsEnabled,
                    fineMode = state.fineMode,
                    onEncoderDragStart = onEncoderDragStart,
                    onEncoderDrag = onEncoderDrag,
                    onEncoderDragEnd = onEncoderDragEnd,
                    onEncoderDialStart = onEncoderDialStart,
                    onEncoderDialRotate = onEncoderDialRotate,
                    onEncoderDialEnd = onEncoderDialEnd,
                    onOk = onOk
                )
            }

            if (state.touchLock) {
                FilledTonalButton(
                    onClick = { onTouchLockChange(false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock Touch Lock")
                }
            }
        }
    }
}

@Composable
private fun FrequencyKeyboardDialog(
    onDismiss: () -> Unit,
    onType: (String, Boolean) -> Unit
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frequency Keyboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tap the frequency field in Mayhem first, then type here."
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = FrequencyInputCodec.sanitizeFrequencyInput(it) },
                    singleLine = true,
                    label = { Text("MHz (example: 433.9200)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                FilledTonalButton(
                    onClick = { onType(input, false) },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Type Only")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onType(input, true) },
                enabled = input.isNotBlank()
            ) {
                Text("Type + OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DonationDialog(
    title: String,
    showCloseAction: Boolean,
    onDismiss: () -> Unit,
    onCloseApp: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val ethAddress = "0xEcA58b1a98B457C2a07ba74e67dc15d26c39698F"
    val btcAddress = "bc1qsd93dmcrc6l3huyyz3yp8qm4arw38rhf6hsw84"
    val usdtEthAddress = "0xEcA58b1a98B457C2a07ba74e67dc15d26c39698F"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("If HackRFPPH2 helps you, donations are appreciated.")
                CopyableAddressRow(
                    label = "ETH",
                    value = ethAddress,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(ethAddress))
                        Toast.makeText(context, "ETH address copied", Toast.LENGTH_SHORT).show()
                    }
                )
                CopyableAddressRow(
                    label = "BTC",
                    value = btcAddress,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(btcAddress))
                        Toast.makeText(context, "BTC address copied", Toast.LENGTH_SHORT).show()
                    }
                )
                CopyableAddressRow(
                    label = "USDT (ERC20)",
                    value = usdtEthAddress,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(usdtEthAddress))
                        Toast.makeText(context, "USDT address copied", Toast.LENGTH_SHORT).show()
                    }
                )
                SelectionContainer {
                    Text(
                        "You can close this popup and keep using the app normally.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (showCloseAction) {
                TextButton(onClick = onCloseApp) {
                    Text("Close app")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Continue")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (showCloseAction) "Keep using app" else "Not now")
            }
        }
    )
}

@Composable
private fun CopyableAddressRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$label: $value", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCopy) {
                Text("Copy $label")
            }
        }
    }
}

@Composable
private fun DPad(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalButton(
            onClick = onUp,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) { Text("UP") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onLeft,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) { Text("LT") }
            FilledTonalButton(
                onClick = onOk,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) { Text("OK") }
            FilledTonalButton(
                onClick = onRight,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) { Text("RT") }
        }

        FilledTonalButton(
            onClick = onDown,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) { Text("DOWN") }
    }
}

@Composable
private fun EncoderStrip(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    fineMode: Boolean,
    onEncoderDragStart: () -> Unit,
    onEncoderDrag: (Float) -> Unit,
    onEncoderDragEnd: () -> Unit,
    onEncoderDialStart: () -> Unit,
    onEncoderDialRotate: (Float) -> Unit,
    onEncoderDialEnd: () -> Unit,
    onOk: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) Color(0xFF1F2A37) else Color(0xFF535353))
                .pointerInput(enabled, fineMode) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            onEncoderDragStart()
                        },
                        onDragEnd = {
                            onEncoderDragEnd()
                        },
                        onDragCancel = {
                            onEncoderDragEnd()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onEncoderDrag(dragAmount.x)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "ENCODER ${if (fineMode) "FINE" else "COARSE"}\nDrag left/right",
                textAlign = TextAlign.Center,
                color = Color.White
            )
        }

        RotaryDial(
            enabled = enabled,
            fineMode = fineMode,
            onDialStart = onEncoderDialStart,
            onDialRotate = onEncoderDialRotate,
            onDialEnd = onEncoderDialEnd,
            onPress = onOk
        )

        FilledTonalButton(
            onClick = onOk,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Encoder Press (OK)")
        }
    }
}

@Composable
private fun RotaryDial(
    enabled: Boolean,
    fineMode: Boolean,
    onDialStart: () -> Unit,
    onDialRotate: (Float) -> Unit,
    onDialEnd: () -> Unit,
    onPress: () -> Unit,
    compact: Boolean = false
) {
    val density = LocalDensity.current
    var heading by remember { mutableFloatStateOf(0f) }
    val ringColor = if (enabled) Color(0xFF203B5A) else Color(0xFF555555)
    val markerColor = if (enabled) Color(0xFF63D3FF) else Color(0xFF9E9E9E)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 140.dp else 188.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151A20))
            .border(1.dp, Color(0xFF2A323D), RoundedCornerShape(18.dp))
            .pointerInput(enabled, fineMode) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        onDialStart()
                    },
                    onDragEnd = {
                        onDialEnd()
                    },
                    onDragCancel = {
                        onDialEnd()
                    },
                    onDrag = { change, _ ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val prev = change.previousPosition - center
                        val curr = change.position - center
                        val prevAngle = atan2(prev.y, prev.x)
                        val currAngle = atan2(curr.y, curr.x)
                        var delta = currAngle - prevAngle
                        if (delta > Math.PI.toFloat()) delta -= (Math.PI * 2f).toFloat()
                        if (delta < -Math.PI.toFloat()) delta += (Math.PI * 2f).toFloat()
                        heading += delta
                        onDialRotate(delta)
                        change.consume()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(if (compact) 118.dp else 160.dp)) {
            val radius = size.minDimension * 0.42f
            drawCircle(
                color = ringColor,
                radius = radius + with(density) { 10.dp.toPx() }
            )
            drawCircle(
                color = Color(0xFF0F141A),
                radius = radius
            )
            drawCircle(
                color = Color(0xFF2A323D),
                radius = radius,
                style = Stroke(width = with(density) { 2.dp.toPx() })
            )

            val markerLen = with(density) { 16.dp.toPx() }
            val markerStart = Offset(
                x = center.x + kotlin.math.cos(heading) * (radius - markerLen),
                y = center.y + kotlin.math.sin(heading) * (radius - markerLen)
            )
            val markerEnd = Offset(
                x = center.x + kotlin.math.cos(heading) * (radius - 2f),
                y = center.y + kotlin.math.sin(heading) * (radius - 2f)
            )
            drawLine(
                color = markerColor,
                start = markerStart,
                end = markerEnd,
                strokeWidth = with(density) { 4.dp.toPx() },
                cap = StrokeCap.Round
            )
        }

        FilledTonalButton(
            onClick = onPress,
            enabled = enabled,
            modifier = Modifier.size(if (compact) 62.dp else 78.dp)
        ) {
            Text("OK")
        }
    }
}
