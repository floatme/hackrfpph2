package com.orbit.app.portapack

import java.util.Locale

object FrequencyInputCodec {
    private const val MAX_INPUT_LENGTH = 24

    fun sanitizeFrequencyInput(raw: String): String {
        val filtered = raw.filter { it.isDigit() || it == '.' }.take(MAX_INPUT_LENGTH)
        if (filtered.isEmpty()) {
            return filtered
        }
        var dotUsed = false
        val out = StringBuilder(filtered.length)
        for (ch in filtered) {
            if (ch == '.') {
                if (dotUsed) continue
                dotUsed = true
            }
            out.append(ch)
        }
        return out.toString()
    }

    fun toKeyboardHexPayload(text: String): String {
        val bytes = text.take(MAX_INPUT_LENGTH).toByteArray(Charsets.US_ASCII)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            // Keep to printable ascii plus backspace.
            if ((v in 32..126) || v == 8) {
                sb.append(String.format(Locale.US, "%02X", v))
            }
        }
        return sb.toString()
    }
}
