package com.orbit.app.portapack

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import kotlin.math.min

data class LinkCapabilities(
    val buttonFast: Boolean = false,
    val touchFast: Boolean = false,
    val frameHalf: Boolean = false
) {
    val turboEnabled: Boolean
        get() = buttonFast || touchFast || frameHalf
}

class MayhemUsbShellClient {
    companion object {
        private const val TAG = "HackRFPPH2.UsbShell"
    }

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null

    private val readBuffer = ByteArray(4096)
    private val pendingLines = ArrayDeque<String>()
    private val pendingChars = StringBuilder(512)
    private var linkCapabilities = LinkCapabilities()

    @Throws(IOException::class)
    fun open(driver: UsbSerialDriver, usbManager: UsbManager) {
        close()
        Log.d(TAG, "Opening USB serial for deviceId=${driver.device.deviceId}")

        val conn = usbManager.openDevice(driver.device)
            ?: throw IOException("USB permission denied")
        val serialPort = driver.ports.firstOrNull()
            ?: throw IOException("No serial port found")

        try {
            serialPort.open(conn)
            serialPort.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            serialPort.dtr = true
            serialPort.rts = true
            // Some phones/OTG stacks need a short settle delay before first write.
            Thread.sleep(120)
        } catch (error: Exception) {
            conn.close()
            throw IOException("Failed opening serial port", error)
        }

        connection = conn
        port = serialPort
        pendingLines.clear()
        pendingChars.setLength(0)
        linkCapabilities = LinkCapabilities()
        drainInput()
        Log.d(TAG, "USB serial open complete")
    }

    fun isOpen(): Boolean = port != null

    fun close() {
        Log.d(TAG, "Closing USB serial")
        try {
            port?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        port = null
        connection = null
        linkCapabilities = LinkCapabilities()
        pendingLines.clear()
        pendingChars.setLength(0)
    }

    @Throws(IOException::class)
    fun queryResolution(): ScreenSize {
        val lines = runCommand("getres", timeoutMs = 3500, maxLines = 16)
        return MayhemFrameCodec.parseResolution(lines)
            ?: throw IOException("Unable to parse screen resolution")
    }

    fun detectLinkCapabilities(): LinkCapabilities {
        val capabilities = try {
            val lines = runCommand("linkcaps", timeoutMs = 900, maxLines = 24)
            parseLinkCapabilities(lines)
        } catch (_: IOException) {
            // Older firmware does not expose linkcaps.
            LinkCapabilities()
        }
        linkCapabilities = capabilities
        Log.i(
            TAG,
            "Link capabilities: buttonFast=${capabilities.buttonFast}, touchFast=${capabilities.touchFast}, frameHalf=${capabilities.frameHalf}"
        )
        return capabilities
    }

    @Throws(IOException::class)
    fun fetchShortFrame(size: ScreenSize): IntArray {
        if (linkCapabilities.frameHalf) {
            return fetchShortFrameHalf(size)
        }
        val lines = runCommand(
            command = "screenframeshort",
            timeoutMs = 2800,
            maxLines = size.height + 16
        )
        return MayhemFrameCodec.decodeShortFrame(lines, size.width, size.height)
    }

    @Throws(IOException::class)
    private fun fetchShortFrameHalf(size: ScreenSize): IntArray {
        val halfHeight = (size.height / 2).coerceAtLeast(1)
        val lines = runCommand(
            command = "screenframeshort2",
            timeoutMs = 1700,
            maxLines = halfHeight + 16
        )
        return MayhemFrameCodec.decodeShortFrame2xUpscaled(lines, size.width, size.height)
    }

    @Throws(IOException::class)
    fun sendButton(button: Int): Boolean {
        if (linkCapabilities.buttonFast) {
            try {
                runCommand("button_fast $button", timeoutMs = 900, maxLines = 8)
                return true
            } catch (_: IOException) {
                Log.w(TAG, "button_fast failed, falling back to button")
            }
        }
        return try {
            runCommand("button $button", timeoutMs = 3000, maxLines = 8)
            true
        } catch (_: IOException) {
            // Some builds occasionally interleave shell output around button commands.
            // Fall back to raw command write so controls are still sent.
            Log.w(TAG, "Button $button command fallback to raw write")
            sendRawCommand(if (linkCapabilities.buttonFast) "button_fast $button" else "button $button")
            if (linkCapabilities.buttonFast) {
                sendRawCommand("button $button")
            }
            false
        }
    }

    @Throws(IOException::class)
    fun sendTouch(x: Int, y: Int): Boolean {
        if (linkCapabilities.touchFast) {
            try {
                runCommand("touch_fast $x $y", timeoutMs = 900, maxLines = 8)
                return true
            } catch (_: IOException) {
                Log.w(TAG, "touch_fast failed, falling back to touch")
            }
        }
        return try {
            runCommand("touch $x $y", timeoutMs = 3000, maxLines = 8)
            true
        } catch (_: IOException) {
            Log.w(TAG, "Touch $x,$y command fallback to raw write")
            sendRawCommand(if (linkCapabilities.touchFast) "touch_fast $x $y" else "touch $x $y")
            if (linkCapabilities.touchFast) {
                sendRawCommand("touch $x $y")
            }
            false
        }
    }

    @Throws(IOException::class)
    fun sendKeyboardText(text: String): Boolean {
        if (text.isEmpty()) {
            return true
        }
        val payload = FrequencyInputCodec.toKeyboardHexPayload(text)
        if (payload.isEmpty()) {
            return false
        }
        return try {
            runCommand("keyboard $payload", timeoutMs = 2600, maxLines = 8)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun parseLinkCapabilities(lines: List<String>): LinkCapabilities {
        var buttonFast = false
        var touchFast = false
        var frameHalf = false
        for (line in lines) {
            val cleaned = line.trim().lowercase()
            when {
                cleaned.startsWith("button_fast=") -> buttonFast = cleaned.endsWith("=1")
                cleaned.startsWith("touch_fast=") -> touchFast = cleaned.endsWith("=1")
                cleaned.startsWith("screenframeshort2=") -> frameHalf = cleaned.endsWith("=1")
            }
        }
        return LinkCapabilities(
            buttonFast = buttonFast,
            touchFast = touchFast,
            frameHalf = frameHalf
        )
    }

    @Throws(IOException::class)
    private fun runCommand(
        command: String,
        timeoutMs: Long,
        maxLines: Int
    ): List<String> {
        val serialPort = port ?: throw IOException("Serial port is not open")
        writeAscii(serialPort, "$command\r\n")

        val lines = ArrayList<String>(min(maxLines, 128))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = pollLine(deadline) ?: continue
            if (line == "ok") {
                return lines
            }
            if (line == "error") {
                throw IOException("Mayhem returned error for: $command")
            }
            if (lines.size >= maxLines) {
                throw IOException("Mayhem response too long for: $command")
            }
            lines.add(line)
        }
        throw IOException("Timeout waiting for response to: $command")
    }

    @Throws(IOException::class)
    private fun writeAscii(serialPort: UsbSerialPort, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset < bytes.size) {
            val chunkLength = min(256, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + chunkLength)
            var wrote = false
            var attempt = 0
            var lastError: Exception? = null
            while (!wrote && attempt < 2) {
                try {
                    val timeout = if (attempt == 0) 2500 else 4500
                    serialPort.write(chunk, timeout)
                    wrote = true
                } catch (error: Exception) {
                    lastError = error
                    if (attempt == 0) {
                        try {
                            Thread.sleep(80)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                attempt += 1
            }
            if (!wrote) {
                throw IOException("Serial write failed", lastError)
            }
            offset += chunkLength
        }
    }

    @Throws(IOException::class)
    private fun sendRawCommand(command: String) {
        val serialPort = port ?: throw IOException("Serial port is not open")
        writeAscii(serialPort, "$command\r\n")
    }

    @Throws(IOException::class)
    private fun pollLine(deadlineMillis: Long): String? {
        if (pendingLines.isNotEmpty()) {
            return pendingLines.removeFirst()
        }

        val serialPort = port ?: throw IOException("Serial port is not open")
        while (System.currentTimeMillis() < deadlineMillis) {
            val bytesRead = try {
                serialPort.read(readBuffer, 150)
            } catch (error: Exception) {
                throw IOException("Serial read failed", error)
            }
            if (bytesRead <= 0) {
                continue
            }
            feedReadBytes(bytesRead)
            if (pendingLines.isNotEmpty()) {
                return pendingLines.removeFirst()
            }
        }
        return null
    }

    private fun feedReadBytes(bytesRead: Int) {
        for (index in 0 until bytesRead) {
            val value = readBuffer[index].toInt() and 0xFF
            if (value == '\n'.code) {
                val raw = pendingChars.toString()
                val line = if (raw.endsWith('\r')) raw.dropLast(1) else raw
                pendingChars.setLength(0)
                pendingLines.add(line)
            } else {
                pendingChars.append(value.toChar())
            }
        }
    }

    private fun drainInput() {
        val serialPort = port ?: return
        repeat(6) {
            val bytesRead = try {
                serialPort.read(readBuffer, 25)
            } catch (_: Exception) {
                return
            }
            if (bytesRead <= 0) {
                return
            }
        }
    }
}
