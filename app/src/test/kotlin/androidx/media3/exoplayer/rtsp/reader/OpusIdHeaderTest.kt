package androidx.media3.exoplayer.rtsp.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class OpusIdHeaderTest {
    @Test
    fun synthesizedHeaderMatchesRfc7845Layout() {
        val header = RtpOpusReader.synthesizeOpusIdHeader(2)
        assertEquals(19, header.size)
        assertEquals("OpusHead", String(header, 0, 8, Charsets.US_ASCII))
        assertEquals(1, header[8].toInt())
        assertEquals(2, header[9].toInt())
        val rate = (header[12].toInt() and 0xFF) or ((header[13].toInt() and 0xFF) shl 8) or
            ((header[14].toInt() and 0xFF) shl 16) or ((header[15].toInt() and 0xFF) shl 24)
        assertEquals(48_000, rate)
        assertEquals(0, header[18].toInt())
    }

    @Test
    fun monoStaysMono() {
        assertEquals(1, RtpOpusReader.synthesizeOpusIdHeader(1)[9].toInt())
    }
}
