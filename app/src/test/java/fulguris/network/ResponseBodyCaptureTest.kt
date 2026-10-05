package fulguris.network

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for [captureBody].
 *
 * The whole point of the tee is that WebView still gets every byte, so those tests mostly check
 * that: a cancelled or truncated capture would silently corrupt page loads.
 */
class ResponseBodyCaptureTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.UTF_8)

    /**
     * Read [stream] to the end, as WebView would.
     */
    private fun drain(stream: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) {
                break
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun `a body shorter than the cap is captured whole and streamed whole`() {
        val original = ascii("hello world")

        val capture = captureBody(ByteArrayInputStream(original), cap = 64)

        assertThat(capture.body.bytes).isEqualTo(original)
        assertThat(capture.body.truncated).isFalse()
        assertThat(drain(capture.stream)).isEqualTo(original)
    }

    @Test
    fun `a body exactly the cap long is not truncated`() {
        val original = ascii("0123456789")

        val capture = captureBody(ByteArrayInputStream(original), cap = original.size)

        assertThat(capture.body.bytes).isEqualTo(original)
        assertThat(capture.body.truncated).isFalse()
        assertThat(drain(capture.stream)).isEqualTo(original)
    }

    @Test
    fun `a body longer than the cap is truncated but still streams whole`() {
        val original = ascii("0123456789abcdefghij")

        val capture = captureBody(ByteArrayInputStream(original), cap = 10)

        assertThat(capture.body.bytes).isEqualTo(ascii("0123456789"))
        assertThat(capture.body.truncated).isTrue()
        // The byte past the cap tells us about the truncation, it must not be lost
        assertThat(drain(capture.stream)).isEqualTo(original)
    }

    @Test
    fun `an empty body is captured as empty`() {
        val capture = captureBody(ByteArrayInputStream(ByteArray(0)), cap = 64)

        assertThat(capture.body.bytes).isEmpty()
        assertThat(capture.body.truncated).isFalse()
        assertThat(drain(capture.stream)).isEmpty()
    }

    @Test
    fun `the captured size is the number of bytes we kept`() {
        val capture = captureBody(ByteArrayInputStream(ByteArray(100)), cap = 30)

        assertThat(capture.body.size).isEqualTo(30)
    }

    @Test
    fun `a read error keeps what we got so far without failing the page load`() {
        val failing = object : InputStream() {
            private var remaining = 5
            private val head = ascii("abcde")

            override fun read(): Int {
                if (remaining == 0) {
                    throw IOException("boom")
                }
                remaining--
                return head[4 - remaining].toInt()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining == 0) {
                    throw IOException("boom")
                }
                // Hand out a single byte at a time so we hit the failure mid capture
                return read().let {
                    b[off] = it.toByte()
                    1
                }
            }
        }

        val capture = captureBody(failing, cap = 64)

        assertThat(capture.body.bytes).isEqualTo(ascii("abcde"))
        assertThat(capture.body.truncated).isFalse()
    }
}
