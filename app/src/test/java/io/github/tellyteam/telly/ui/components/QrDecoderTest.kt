package io.github.tellyteam.telly.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.tellyteam.telly.data.sync.SyncJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class QrDecoderTest {
    /** A TV's code drawn as a camera would see it: grey surroundings, rows padded past the width. */
    private fun frame(text: String, width: Int, height: Int, rowStride: Int): ByteBuffer {
        val size = 300
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val bytes = ByteArray(rowStride * height) { 0x70 }
        val left = (width - size) / 2
        val top = (height - size) / 2
        for (y in 0 until size) for (x in 0 until size) {
            bytes[(top + y) * rowStride + left + x] = if (matrix[x, y]) 0x10 else 0xF0.toByte()
        }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun readsATvsCode() {
        val url = SyncJoin.new().url
        assertEquals(url, QrDecoder().decode(frame(url, 640, 480, 704), 640, 480, 704))
        assertEquals(url, SyncJoin.parse(QrDecoder().decode(frame(url, 640, 480, 640), 640, 480, 640)!!)?.url)
    }

    @Test
    fun skipsAFrameWithNoCode() {
        assertNull(QrDecoder().decode(ByteBuffer.wrap(ByteArray(640 * 480) { 0x70 }), 640, 480, 640))
    }
}
