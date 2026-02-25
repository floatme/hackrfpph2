package com.orbit.app.portapack

import android.util.Log
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

enum class ScreenProfile(
    val label: String,
    val forcedSize: ScreenSize?
) {
    Auto("Auto (detected)", null),
    H2Portrait("H2 240x320", ScreenSize(240, 320)),
    H2Landscape("H2 320x240", ScreenSize(320, 240))
}

data class UsbDeviceUi(
    val deviceId: Int,
    val title: String,
    val subtitle: String,
    val hasPermission: Boolean
)

data class PortaPackRemoteUiState(
    val availableDevices: List<UsbDeviceUi> = emptyList(),
    val selectedDeviceId: Int? = null,
    val connectedDeviceId: Int? = null,
    val connected: Boolean = false,
    val status: String = "Disconnected",
    val detectedScreenSize: ScreenSize = ScreenSize(240, 320),
    val screenSize: ScreenSize = ScreenSize(240, 320),
    val screenProfile: ScreenProfile = ScreenProfile.Auto,
    val fullscreenMode: Boolean = false,
    val fullscreenControlsVisible: Boolean = true,
    val frameBitmap: Bitmap? = null,
    val frameToken: Long = 0,
    val touchLock: Boolean = false,
    val autoReconnect: Boolean = true,
    val turboLinkActive: Boolean = false,
    val fineMode: Boolean = false,
    val coarsePixelsPerStep: Int = 8,
    val finePixelsPerStep: Int = 16,
    val lastControl: String = "-"
)

class PortaPackRemoteController(
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "HackRFPPH2.Controller"
        private const val MAX_STREAM_ERRORS_BEFORE_RECONNECT = 4
    }

    private val prober = UsbSerialProber.getDefaultProber()
    private val shell = MayhemUsbShellClient()
    private val ioMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val driversByDeviceId = LinkedHashMap<Int, UsbSerialDriver>()
    private val lastActionMs = HashMap<Int, Long>()

    private var streamJob: Job? = null
    private var reconnectJob: Job? = null
    private var cachedBitmap: Bitmap? = null
    private var permissionRequester: ((UsbDevice) -> Unit)? = null
    private var encoderDragAccumulatorPx = 0f
    private var encoderDialAccumulatorRad = 0f
    private var streamErrorStreak = 0
    private var reconnectBackoffMs = 1400L
    @Volatile
    private var holdStreamUntilMs = 0L
    @Volatile
    private var manualDisconnectRequested = false
    private val pendingControlOps = AtomicInteger(0)

    private val _state = MutableStateFlow(PortaPackRemoteUiState())
    val state: StateFlow<PortaPackRemoteUiState> = _state.asStateFlow()

    fun shutdown() {
        streamJob?.cancel()
        reconnectJob?.cancel()
        scope.launch {
            ioMutex.withLock {
                shell.close()
            }
            cachedBitmap?.recycle()
            cachedBitmap = null
        }
    }

    fun refreshDevices() {
        val drivers = prober.findAllDrivers(usbManager)
        driversByDeviceId.clear()
        drivers.forEach { driver -> driversByDeviceId[driver.device.deviceId] = driver }

        val rows = drivers.map { driver ->
            val device = driver.device
            val title = "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
            val subtitle = "${device.deviceName} (${driver.ports.size} port)"
            UsbDeviceUi(
                deviceId = device.deviceId,
                title = title,
                subtitle = subtitle,
                hasPermission = usbManager.hasPermission(device)
            )
        }

        _state.update { current ->
            val selected = when {
                rows.isEmpty() -> null
                current.selectedDeviceId != null && rows.any { it.deviceId == current.selectedDeviceId } -> current.selectedDeviceId
                else -> rows.first().deviceId
            }
            current.copy(
                availableDevices = rows,
                selectedDeviceId = selected,
                status = if (rows.isEmpty() && !current.connected) "No USB serial device found" else current.status
            )
        }
    }

    fun enableDemoConnectedState() {
        val demoSize = ScreenSize(240, 320)
        val bitmap = ensureBitmap(demoSize)
        bitmap.setPixels(
            createDemoFramePixels(demoSize),
            0,
            demoSize.width,
            0,
            0,
            demoSize.width,
            demoSize.height
        )
        _state.update {
            it.copy(
                availableDevices = listOf(
                    UsbDeviceUi(
                        deviceId = 1,
                        title = "USB 1d50:6018",
                        subtitle = "HackRF One + PortaPack H2 (demo)",
                        hasPermission = true
                    )
                ),
                selectedDeviceId = 1,
                connectedDeviceId = 1,
                connected = true,
                status = "Connected (240x320) Demo",
                detectedScreenSize = demoSize,
                screenSize = demoSize,
                screenProfile = ScreenProfile.H2Portrait,
                turboLinkActive = true,
                frameBitmap = bitmap,
                frameToken = it.frameToken + 1,
                lastControl = "DEMO"
            )
        }
    }

    fun setSelectedDevice(deviceId: Int) {
        _state.update { it.copy(selectedDeviceId = deviceId) }
    }

    fun connectSelected(onPermissionRequired: (UsbDevice) -> Unit) {
        permissionRequester = onPermissionRequired
        manualDisconnectRequested = false

        val selectedDeviceId = state.value.selectedDeviceId
            ?: run {
                _state.update { it.copy(status = "Select a USB device first") }
                return
            }
        val driver = driversByDeviceId[selectedDeviceId]
            ?: run {
                refreshDevices()
                _state.update { it.copy(status = "USB device disappeared") }
                return
            }
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission required for deviceId=${device.deviceId}")
            _state.update { it.copy(status = "Waiting for USB permission...") }
            onPermissionRequired(device)
            return
        }

        reconnectJob?.cancel()
        scope.launch {
            try {
                Log.i(TAG, "Connecting to deviceId=$selectedDeviceId")
                var connectResult: Pair<ScreenSize, LinkCapabilities>? = null
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        connectResult = ioMutex.withLock {
                            shell.open(driver, usbManager)
                            val detectedSize = shell.queryResolution()
                            val capabilities = shell.detectLinkCapabilities()
                            detectedSize to capabilities
                        }
                        break
                    } catch (error: Exception) {
                        lastError = error
                        Log.w(TAG, "Connect attempt $attempt/3 failed", error)
                        ioMutex.withLock {
                            shell.close()
                        }
                        if (attempt < 3) {
                            _state.update {
                                it.copy(status = "Connect retry $attempt/3...")
                            }
                            delay(260L * attempt)
                        }
                    }
                }
                val (size, linkCaps) = connectResult
                    ?: throw (lastError ?: IOException("Unable to open serial link"))
                streamErrorStreak = 0
                reconnectBackoffMs = 1400L
                _state.update {
                    val resolved = resolveScreenSize(it.screenProfile, size)
                    it.copy(
                        connected = true,
                        connectedDeviceId = selectedDeviceId,
                        status = if (linkCaps.turboEnabled) {
                            "Connected (${size.width}x${size.height}) Turbo"
                        } else {
                            "Connected (${size.width}x${size.height})"
                        },
                        detectedScreenSize = size,
                        screenSize = resolved,
                        turboLinkActive = linkCaps.turboEnabled
                    )
                }
                startStreaming()
            } catch (error: Exception) {
                Log.w(TAG, "Connect failed for deviceId=$selectedDeviceId", error)
                ioMutex.withLock {
                    shell.close()
                }
                _state.update {
                    it.copy(
                        connected = false,
                        connectedDeviceId = null,
                        status = connectFailureMessage(error),
                        turboLinkActive = false
                    )
                }
                scheduleReconnect("Connect failed")
            }
        }
    }

    fun disconnect(
        reason: String = "Disconnected",
        manual: Boolean = true
    ) {
        if (manual) {
            manualDisconnectRequested = true
        }
        Log.i(TAG, "Disconnect requested. manual=$manual reason=$reason")
        streamJob?.cancel()
        streamJob = null
        if (manual) {
            reconnectJob?.cancel()
        }
        scope.launch {
            ioMutex.withLock {
                shell.close()
            }
            _state.update {
                it.copy(
                    connected = false,
                    connectedDeviceId = null,
                    status = reason,
                    turboLinkActive = false
                )
            }
        }
    }

    fun onUsbDetached(deviceId: Int) {
        Log.w(TAG, "USB detached for deviceId=$deviceId")
        val connectedId = state.value.connectedDeviceId
        if (connectedId != null && connectedId == deviceId) {
            disconnect(reason = "USB device detached", manual = false)
            scheduleReconnect("USB detached")
        }
        refreshDevices()
    }

    fun setTouchLock(enabled: Boolean) {
        _state.update { it.copy(touchLock = enabled) }
    }

    fun setFullscreenMode(enabled: Boolean) {
        _state.update { it.copy(fullscreenMode = enabled) }
    }

    fun setFullscreenControlsVisible(enabled: Boolean) {
        _state.update { it.copy(fullscreenControlsVisible = enabled) }
    }

    fun setScreenProfile(profile: ScreenProfile) {
        _state.update { current ->
            val resolved = resolveScreenSize(profile, current.detectedScreenSize)
            current.copy(
                screenProfile = profile,
                screenSize = resolved,
                status = "Screen profile: ${profile.label}"
            )
        }
    }

    fun toggleFineMode() {
        _state.update { it.copy(fineMode = !it.fineMode) }
    }

    fun sendUp() = sendDebouncedButton(MayhemButtonMap.UP, 1)
    fun sendDown() = sendDebouncedButton(MayhemButtonMap.DOWN, 2)
    fun sendLeft() = sendDebouncedButton(MayhemButtonMap.LEFT, 3)
    fun sendRight() = sendDebouncedButton(MayhemButtonMap.RIGHT, 4)
    fun sendOk() = sendDebouncedButton(MayhemButtonMap.OK, 5)
    fun sendKeyboardText(text: String, applyAfter: Boolean) {
        val current = state.value
        if (!current.connected || current.touchLock) {
            return
        }
        val filtered = FrequencyInputCodec.sanitizeFrequencyInput(text)
        if (filtered.isEmpty()) {
            return
        }
        scope.launch {
            pendingControlOps.incrementAndGet()
            try {
                holdStreamForInput()
                val acked = ioMutex.withLock {
                    shell.sendKeyboardText(filtered)
                }
                if (!acked) {
                    _state.update {
                        it.copy(status = "Keyboard input not supported by current firmware")
                    }
                    return@launch
                }
                announceControl("KBD $filtered")
            } catch (_: Exception) {
                _state.update { it.copy(status = "Control command failed (KBD)") }
                return@launch
            } finally {
                pendingControlOps.decrementAndGet()
            }
            if (applyAfter) {
                sendOk()
            }
        }
    }

    fun sendTouch(x: Int, y: Int) {
        val current = state.value
        if (!current.connected || current.touchLock) {
            return
        }
        val clampedX = x.coerceIn(0, current.screenSize.width - 1)
        val clampedY = y.coerceIn(0, current.screenSize.height - 1)
        scope.launch {
            sendTouchBlocking(clampedX, clampedY)
        }
    }

    fun onEncoderDragStart() {
        encoderDragAccumulatorPx = 0f
    }

    fun onEncoderDrag(deltaX: Float) {
        val current = state.value
        if (!current.connected || current.touchLock) {
            return
        }

        val pixelsPerStep = if (current.fineMode) {
            current.finePixelsPerStep.toFloat()
        } else {
            current.coarsePixelsPerStep.toFloat()
        }

        encoderDragAccumulatorPx += deltaX
        var steps = (encoderDragAccumulatorPx / pixelsPerStep).toInt()
        if (steps == 0) {
            return
        }
        // Hard cap to avoid flooding shell commands from large gesture jumps.
        steps = steps.coerceIn(-5, 5)
        encoderDragAccumulatorPx -= (steps * pixelsPerStep)
        sendEncoderSteps(steps)
    }

    fun onEncoderDragEnd() {
        encoderDragAccumulatorPx = 0f
    }

    fun onEncoderDialStart() {
        encoderDialAccumulatorRad = 0f
    }

    fun onEncoderDialRotate(deltaRadians: Float) {
        val current = state.value
        if (!current.connected || current.touchLock) {
            return
        }
        val radiansPerStep = if (current.fineMode) {
            // Fine mode: require more movement per step for better precision.
            0.28f
        } else {
            0.18f
        }
        encoderDialAccumulatorRad += deltaRadians
        var steps = (encoderDialAccumulatorRad / radiansPerStep).toInt()
        if (steps == 0) {
            return
        }
        steps = steps.coerceIn(-6, 6)
        encoderDialAccumulatorRad -= (steps * radiansPerStep)
        sendEncoderSteps(steps)
    }

    fun onEncoderDialEnd() {
        encoderDialAccumulatorRad = 0f
    }

    private fun sendDebouncedButton(button: Int, actionKey: Int, debounceMs: Long = 80L) {
        val current = state.value
        if (!current.connected || current.touchLock) {
            return
        }
        val now = System.currentTimeMillis()
        val previous = lastActionMs[actionKey] ?: 0L
        if ((now - previous) < debounceMs) {
            return
        }
        lastActionMs[actionKey] = now
        sendButton(button)
    }

    private fun sendEncoderSteps(steps: Int) {
        if (steps == 0) {
            return
        }
        val current = state.value
        if (!current.connected || current.touchLock) {
            return
        }
        val button = if (steps > 0) MayhemButtonMap.ENCODER_CW else MayhemButtonMap.ENCODER_CCW
        val repeats = abs(steps)
        scope.launch {
            repeat(repeats) {
                sendButtonBlocking(button)
                delay(14)
            }
        }
        announceControl("ENC ${if (steps > 0) "+" else ""}$steps")
    }

    private fun sendButton(button: Int) {
        scope.launch {
            sendButtonBlocking(button)
        }
    }

    private suspend fun sendButtonBlocking(button: Int) {
        pendingControlOps.incrementAndGet()
        try {
            holdStreamForInput()
            val acked = ioMutex.withLock {
                shell.sendButton(button)
            }
            announceControl(
                when (button) {
                    MayhemButtonMap.UP -> "UP"
                    MayhemButtonMap.DOWN -> "DOWN"
                    MayhemButtonMap.LEFT -> "LEFT"
                    MayhemButtonMap.RIGHT -> "RIGHT"
                    MayhemButtonMap.OK -> "OK"
                    MayhemButtonMap.ENCODER_CCW -> "ENC -"
                    MayhemButtonMap.ENCODER_CW -> "ENC +"
                    else -> "BTN $button"
                } + if (acked) "" else " (raw)"
            )
        } catch (_: Exception) {
            _state.update { it.copy(status = "Control command failed (BTN $button)") }
        } finally {
            pendingControlOps.decrementAndGet()
        }
    }

    private suspend fun sendTouchBlocking(x: Int, y: Int) {
        pendingControlOps.incrementAndGet()
        try {
            holdStreamForInput()
            val acked = ioMutex.withLock {
                shell.sendTouch(x, y)
            }
            announceControl("TOUCH $x,$y" + if (acked) "" else " (raw)")
        } catch (_: Exception) {
            _state.update { it.copy(status = "Control command failed (TOUCH $x,$y)") }
        } finally {
            pendingControlOps.decrementAndGet()
        }
    }

    private fun startStreaming() {
        streamJob?.cancel()
        streamJob = scope.launch {
            while (state.value.connected) {
                val now = System.currentTimeMillis()
                if (now < holdStreamUntilMs) {
                    delay((holdStreamUntilMs - now).coerceAtMost(180))
                    continue
                }
                if (pendingControlOps.get() > 0) {
                    delay(20)
                    continue
                }
                val size = state.value.screenSize
                try {
                    if (!ioMutex.tryLock()) {
                        delay(20)
                        continue
                    }
                    val pixels = try {
                        shell.fetchShortFrame(size)
                    } finally {
                        ioMutex.unlock()
                    }
                    val bitmap = ensureBitmap(size)
                    bitmap.setPixels(
                        pixels,
                        0,
                        size.width,
                        0,
                        0,
                        size.width,
                        size.height
                    )
                    _state.update {
                        it.copy(
                            frameBitmap = bitmap,
                            frameToken = it.frameToken + 1
                        )
                    }
                    streamErrorStreak = 0
                } catch (error: IOException) {
                    streamErrorStreak += 1
                    Log.w(TAG, "Stream I/O error streak=$streamErrorStreak", error)
                    if (state.value.screenProfile != ScreenProfile.Auto) {
                        _state.update {
                            val fallback = it.detectedScreenSize
                            it.copy(
                                status = "Profile mismatch; fallback to Auto (${fallback.width}x${fallback.height})",
                                screenProfile = ScreenProfile.Auto,
                                screenSize = fallback
                            )
                        }
                        delay(140)
                        continue
                    }
                    if (streamErrorStreak >= MAX_STREAM_ERRORS_BEFORE_RECONNECT) {
                        reconnectFromStreamFailure()
                        break
                    }
                    _state.update {
                        it.copy(status = "Stream error: ${error.message ?: "io failure"}")
                    }
                    delay(350)
                } catch (_: Exception) {
                    streamErrorStreak += 1
                    _state.update {
                        it.copy(status = "Stream stopped")
                    }
                    delay(350)
                }

                // Keep enough breathing room so queued control events can run.
                delay(if (state.value.turboLinkActive) 28 else 80)
            }
        }
    }

    private fun holdStreamForInput() {
        holdStreamUntilMs = System.currentTimeMillis() + if (state.value.turboLinkActive) 110 else 300
    }

    private suspend fun reconnectFromStreamFailure() {
        Log.w(TAG, "Stream unstable; forcing reconnect")
        ioMutex.withLock {
            shell.close()
        }
        _state.update {
            it.copy(
                connected = false,
                connectedDeviceId = null,
                status = "Stream unstable; reconnecting..."
            )
        }
        scheduleReconnect("Stream unstable")
    }

    private fun scheduleReconnect(trigger: String) {
        if (manualDisconnectRequested || !state.value.autoReconnect) {
            return
        }
        if (reconnectJob?.isActive == true) {
            return
        }
        val requester = permissionRequester ?: return
        reconnectJob = scope.launch {
            val delayMs = reconnectBackoffMs
            _state.update {
                it.copy(status = "$trigger. Reconnecting in ${delayMs / 1000.0}s...")
            }
            delay(delayMs)
            refreshDevices()
            if (!manualDisconnectRequested && !state.value.connected) {
                connectSelected(requester)
            }
            reconnectBackoffMs = (reconnectBackoffMs * 2L).coerceAtMost(9000L)
        }
    }

    private fun announceControl(control: String) {
        _state.update { it.copy(lastControl = control) }
    }

    private fun resolveScreenSize(profile: ScreenProfile, detected: ScreenSize): ScreenSize {
        return profile.forcedSize ?: detected
    }

    private fun ensureBitmap(size: ScreenSize): Bitmap {
        val current = cachedBitmap
        if (current != null && current.width == size.width && current.height == size.height) {
            return current
        }
        current?.recycle()
        val next = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        cachedBitmap = next
        return next
    }

    private fun createDemoFramePixels(size: ScreenSize): IntArray {
        val pixels = IntArray(size.width * size.height)
        for (y in 0 until size.height) {
            val rowOffset = y * size.width
            for (x in 0 until size.width) {
                val color = when {
                    y < 22 -> 0xFF20252B.toInt()
                    y < 44 -> 0xFF304152.toInt()
                    y < 210 -> {
                        val stripe = ((x / 20) + (y / 18)) % 2
                        if (stripe == 0) 0xFF182028.toInt() else 0xFF21303D.toInt()
                    }
                    else -> 0xFF0F141A.toInt()
                }
                pixels[rowOffset + x] = color
            }
        }
        return pixels
    }

    private fun connectFailureMessage(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            val name = current.javaClass.simpleName.lowercase()
            val msg = (current.message ?: "").lowercase()
            if (name.contains("serialtimeout") || msg.contains("rc=-1")) {
                return "Connect failed: USB busy. Close other SDR apps (RTL/MyHackRF) and retry."
            }
            current = current.cause
        }
        return "Connect failed: ${error.message ?: "unknown error"}"
    }
}
