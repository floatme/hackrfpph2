package com.orbit.app.portapack

import org.junit.Assert.assertEquals
import org.junit.Test

class FrequencyInputCodecTest {
    @Test
    fun sanitizeFrequencyInput_keepsDigitsAndSingleDot() {
        assertEquals("433.9200", FrequencyInputCodec.sanitizeFrequencyInput("433.9200"))
        assertEquals("433.9200", FrequencyInputCodec.sanitizeFrequencyInput("4a3b3.9x2!0@0#"))
        assertEquals("12.34", FrequencyInputCodec.sanitizeFrequencyInput("12..3.4"))
    }

    @Test
    fun sanitizeFrequencyInput_handlesEdgeCases() {
        assertEquals("", FrequencyInputCodec.sanitizeFrequencyInput(""))
        assertEquals("", FrequencyInputCodec.sanitizeFrequencyInput("abcxyz"))
        assertEquals(".123", FrequencyInputCodec.sanitizeFrequencyInput("..1.2.3"))
    }

    @Test
    fun toKeyboardHexPayload_encodesPrintableAscii() {
        assertEquals("3433332E39323030", FrequencyInputCodec.toKeyboardHexPayload("433.9200"))
        assertEquals("08", FrequencyInputCodec.toKeyboardHexPayload("\b"))
    }

    @Test
    fun toKeyboardHexPayload_filtersUnsupportedBytes() {
        assertEquals("3132332E34", FrequencyInputCodec.toKeyboardHexPayload("123.4\u0001\u0002"))
    }
}
