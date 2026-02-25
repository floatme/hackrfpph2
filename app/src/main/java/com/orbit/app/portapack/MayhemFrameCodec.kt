package com.orbit.app.portapack

import kotlin.math.max
import kotlin.math.min

data class ScreenSize(
    val width: Int,
    val height: Int
)

object MayhemFrameCodec {
    private const val asciiOffset = 32
    private const val maxEncoded = 63

    fun parseResolution(lines: List<String>): ScreenSize? {
        for (line in lines) {
            val cleaned = line.trim()
            val xIndex = cleaned.indexOf('x')
            if (xIndex <= 0 || xIndex >= cleaned.length - 1) {
                continue
            }
            val w = cleaned.substring(0, xIndex).toIntOrNull() ?: continue
            val h = cleaned.substring(xIndex + 1).toIntOrNull() ?: continue
            if (w > 0 && h > 0) {
                return ScreenSize(w, h)
            }
        }
        return null
    }

    fun decodeShortFrame(
        lines: List<String>,
        width: Int,
        height: Int
    ): IntArray {
        require(width > 0) { "width must be > 0" }
        require(height > 0) { "height must be > 0" }

        val usableRows = lines.filter { it.length >= width }
        require(usableRows.size >= height) {
            "Expected $height rows with >=$width chars, got ${usableRows.size}"
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = usableRows[y]
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = decodeRgb6(row[x])
            }
        }
        return pixels
    }

    fun decodeShortFrame2xUpscaled(
        lines: List<String>,
        width: Int,
        height: Int
    ): IntArray {
        require(width > 0) { "width must be > 0" }
        require(height > 0) { "height must be > 0" }

        val halfWidth = max(1, width / 2)
        val halfHeight = max(1, height / 2)
        val usableRows = lines.filter { it.length >= halfWidth }
        require(usableRows.size >= halfHeight) {
            "Expected $halfHeight rows with >=$halfWidth chars, got ${usableRows.size}"
        }

        val sampled = IntArray(halfWidth * halfHeight)
        for (y in 0 until halfHeight) {
            val row = usableRows[y]
            val rowOffset = y * halfWidth
            for (x in 0 until halfWidth) {
                sampled[rowOffset + x] = decodeRgb6(row[x])
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val sy = min(halfHeight - 1, y / 2)
            val destOffset = y * width
            val srcOffset = sy * halfWidth
            for (x in 0 until width) {
                val sx = min(halfWidth - 1, x / 2)
                pixels[destOffset + x] = sampled[srcOffset + sx]
            }
        }
        return pixels
    }

    private fun decodeRgb6(ch: Char): Int {
        val encoded = (ch.code - asciiOffset).coerceIn(0, maxEncoded)
        val r = ((encoded shr 4) and 0x03) * 85
        val g = ((encoded shr 2) and 0x03) * 85
        val b = (encoded and 0x03) * 85
        return argb(r, g, b)
    }

    private fun argb(r: Int, g: Int, b: Int): Int {
        val rr = max(0, r) and 0xFF
        val gg = max(0, g) and 0xFF
        val bb = max(0, b) and 0xFF
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}

object MayhemButtonMap {
    const val RIGHT = 1
    const val LEFT = 2
    const val DOWN = 3
    const val UP = 4
    const val OK = 5
    const val ENCODER_CCW = 7
    const val ENCODER_CW = 8
}
