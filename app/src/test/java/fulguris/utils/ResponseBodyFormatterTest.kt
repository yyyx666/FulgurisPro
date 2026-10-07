package fulguris.utils

import fulguris.SDK_VERSION
import fulguris.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [formatBody], [prettyPrintJson] and [hexDump].
 *
 * Robolectric is needed for `org.json` and for its real (non stubbed) implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class ResponseBodyFormatterTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `a text body is decoded as text`() {
        val formatted = formatBody(ascii("hello \"world\""), "text/plain")

        assertThat(formatted.kind).isEqualTo(BodyKind.TEXT)
        assertThat(formatted.text).isEqualTo("hello \"world\"")
    }

    @Test
    fun `html is decoded as text`() {
        val formatted = formatBody(ascii("<p>hi</p>"), "text/html; charset=utf-8")

        assertThat(formatted.kind).isEqualTo(BodyKind.TEXT)
        assertThat(formatted.text).isEqualTo("<p>hi</p>")
    }

    @Test
    fun `a json body is pretty printed`() {
        val formatted = formatBody(ascii("{\"a\":1,\"b\":[2,3]}"), "application/json")

        assertThat(formatted.kind).isEqualTo(BodyKind.JSON)
        assertThat(formatted.text).contains("\n")
        assertThat(formatted.text).contains("\"a\": 1")
    }

    @Test
    fun `a json array body is pretty printed`() {
        val formatted = formatBody(ascii("[1,2]"), "application/json")

        assertThat(formatted.kind).isEqualTo(BodyKind.JSON)
        assertThat(formatted.text).contains("\n")
    }

    @Test
    fun `a json content type with a suffix is still json`() {
        val formatted = formatBody(ascii("{\"a\":1}"), "application/vnd.api+json")

        assertThat(formatted.kind).isEqualTo(BodyKind.JSON)
    }

    @Test
    fun `a json content type announcing its charset is still json`() {
        val formatted = formatBody(ascii("{\"a\":1}"), "application/json; charset=utf-8")

        assertThat(formatted.kind).isEqualTo(BodyKind.JSON)
    }

    @Test
    fun `a json body is json whatever the server called it`() {
        // Servers ship JSON as octet-stream, as text/plain, and with no content type at all; the
        // body is the only evidence that cannot be wrong about what it holds
        for (mimeType in listOf("application/octet-stream", "text/plain", null)) {
            val formatted = formatBody(ascii("{\"a\":1}"), mimeType)

            assertThat(formatted.kind).describedAs("a body served as %s", mimeType)
                .isEqualTo(BodyKind.JSON)
        }
    }

    @Test
    fun `a binary body that opens on neither an object nor an array stays binary`() {
        // A gzip stream starts with 0x1F, a sentence with a letter: neither is JSON
        assertThat(formatBody(byteArrayOf(0x1F, 0x8B.toByte(), 0x08), "application/octet-stream").kind)
            .isEqualTo(BodyKind.BINARY)
        assertThat(formatBody(ascii("plain words"), "text/plain").kind).isEqualTo(BodyKind.TEXT)
    }

    @Test
    fun `a json body with a byte order mark is still json`() {
        val body = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ascii("{\"a\":1}")

        assertThat(formatBody(body, "application/json").kind).isEqualTo(BodyKind.JSON)
    }

    @Test
    fun `invalid json falls back to raw text`() {
        val formatted = formatBody(ascii("not json at all"), "application/json")

        assertThat(formatted.kind).isEqualTo(BodyKind.TEXT)
        assertThat(formatted.text).isEqualTo("not json at all")
    }

    @Test
    fun `an image body is reported as an image`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        val formatted = formatBody(png, "image/png")

        assertThat(formatted.kind).isEqualTo(BodyKind.IMAGE)
        assertThat(formatted.text).isNull()
    }

    @Test
    fun `an unknown binary body is reported as binary`() {
        val formatted = formatBody(byteArrayOf(1, 2, 3, 4), "application/octet-stream")

        assertThat(formatted.kind).isEqualTo(BodyKind.BINARY)
        assertThat(formatted.text).isNull()
    }

    @Test
    fun `a missing content type is treated as binary`() {
        assertThat(formatBody(ascii("whatever"), null).kind).isEqualTo(BodyKind.BINARY)
    }

    @Test
    fun `the advertised charset is honored`() {
        val latin1 = "café".toByteArray(Charsets.ISO_8859_1)

        val formatted = formatBody(latin1, "text/plain", charset = "ISO-8859-1")

        assertThat(formatted.text).isEqualTo("café")
    }

    @Test
    fun `an unknown charset falls back to utf-8 instead of failing`() {
        val formatted = formatBody(ascii("hello"), "text/plain", charset = "no-such-charset")

        assertThat(formatted.text).isEqualTo("hello")
    }

    @Test
    fun `pretty printing a non json document yields null`() {
        assertThat(prettyPrintJson("just text")).isNull()
        assertThat(prettyPrintJson("")).isNull()
    }

    @Test
    fun `a hex dump lists offsets bytes and ascii`() {
        val dump = hexDump(ascii("AB").plus(byteArrayOf(0x00, 0x1F)), maxBytes = 16)

        assertThat(dump).startsWith("00000000  41 42 00 1F")
        assertThat(dump).endsWith("AB..")
    }

    @Test
    fun `a hex dump only renders the bytes we ask for`() {
        val dump = hexDump(ByteArray(40), maxBytes = 32)

        // Two lines of sixteen bytes, no third line
        assertThat(dump.lines()).hasSize(2)
    }
}
