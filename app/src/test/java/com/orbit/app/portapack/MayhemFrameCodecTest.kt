package com.orbit.app.portapack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MayhemFrameCodecTest {
    @Test
    fun parseResolutionParsesValidLine() {
        val size = MayhemFrameCodec.parseResolution(
            listOf(
                "noise",
                "240x320"
            )
        )
        assertNotNull(size)
        assertEquals(240, size?.width)
        assertEquals(320, size?.height)
    }

    @Test
    fun decodeShortFrameMapsExpectedColors() {
        val lines = listOf(
            "${enc(0, 0, 0)}${enc(3, 0, 0)}",
            "${enc(0, 3, 0)}${enc(0, 0, 3)}"
        )
        val pixels = MayhemFrameCodec.decodeShortFrame(lines, width = 2, height = 2)
        assertEquals(0xFF000000.toInt(), pixels[0])
        assertEquals(0xFFFF0000.toInt(), pixels[1])
        assertEquals(0xFF00FF00.toInt(), pixels[2])
        assertEquals(0xFF0000FF.toInt(), pixels[3])
    }

    private fun enc(r: Int, g: Int, b: Int): Char {
        val v = ((r and 0x03) shl 4) or ((g and 0x03) shl 2) or (b and 0x03)
        return (v + 32).toChar()
    }
}
