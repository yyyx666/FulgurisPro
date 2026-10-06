package fulguris.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for [requestType] and [urlMatchesQuery].
 *
 * The type filter is only as good as this: a request sorted into the wrong category is a request
 * the user cannot find again, so each source it reads is pinned down on its own.
 */
class RequestFilteringTest {

    @Test
    fun `a content type decides on its own`() {
        assertThat(requestType("text/html", null, "https://example.com/")).isEqualTo(RequestType.HTML)
        assertThat(requestType("text/css", null, "https://example.com/")).isEqualTo(RequestType.CSS)
        assertThat(requestType("application/javascript", null, "https://example.com/")).isEqualTo(RequestType.JS)
        assertThat(requestType("image/png", null, "https://example.com/")).isEqualTo(RequestType.IMAGES)
        assertThat(requestType("video/mp4", null, "https://example.com/")).isEqualTo(RequestType.MEDIA)
        assertThat(requestType("font/woff2", null, "https://example.com/")).isEqualTo(RequestType.FONTS)
    }

    @Test
    fun `every content type a script can be served as lands in JS`() {
        assertThat(requestType("text/javascript", null, "https://example.com/")).isEqualTo(RequestType.JS)
        assertThat(requestType("application/x-javascript", null, "https://example.com/")).isEqualTo(RequestType.JS)
        assertThat(requestType("application/ecmascript", null, "https://example.com/")).isEqualTo(RequestType.JS)
    }

    @Test
    fun `a font content type only served as an application lands in fonts`() {
        assertThat(requestType("application/font-woff", null, "https://example.com/")).isEqualTo(RequestType.FONTS)
        assertThat(requestType("application/vnd.ms-fontobject", null, "https://example.com/")).isEqualTo(RequestType.FONTS)
    }

    @Test
    fun `a content type is read without its parameters`() {
        assertThat(requestType("text/html; charset=utf-8", null, "https://example.com/")).isEqualTo(RequestType.HTML)
        assertThat(requestType("Text/CSS ; charset=UTF-8", null, "https://example.com/")).isEqualTo(RequestType.CSS)
    }

    @Test
    fun `a json answer is something a script asked for`() {
        assertThat(requestType("application/json", null, "https://example.com/api")).isEqualTo(RequestType.XHR)
        assertThat(requestType("application/ld+json", null, "https://example.com/")).isEqualTo(RequestType.XHR)
    }

    @Test
    fun `a fetch destination decides when the content type does not`() {
        assertThat(requestType(null, "document", "https://example.com/")).isEqualTo(RequestType.HTML)
        assertThat(requestType(null, "style", "https://example.com/")).isEqualTo(RequestType.CSS)
        assertThat(requestType(null, "script", "https://example.com/")).isEqualTo(RequestType.JS)
        assertThat(requestType(null, "image", "https://example.com/")).isEqualTo(RequestType.IMAGES)
        assertThat(requestType(null, "font", "https://example.com/")).isEqualTo(RequestType.FONTS)
        assertThat(requestType(null, "video", "https://example.com/")).isEqualTo(RequestType.MEDIA)
    }

    @Test
    fun `the empty destination Chromium sends for a fetch lands in XHR`() {
        assertThat(requestType(null, "empty", "https://example.com/api/items")).isEqualTo(RequestType.XHR)
    }

    @Test
    fun `a content type wins over the fetch destination`() {
        assertThat(requestType("text/css", "script", "https://example.com/")).isEqualTo(RequestType.CSS)
    }

    @Test
    fun `a websocket is recognized by its scheme`() {
        assertThat(requestType(null, null, "wss://example.com/socket")).isEqualTo(RequestType.WEBSOCKET)
        assertThat(requestType(null, null, "ws://example.com/socket")).isEqualTo(RequestType.WEBSOCKET)
        assertThat(requestType(null, "websocket", "https://example.com/socket")).isEqualTo(RequestType.WEBSOCKET)
    }

    @Test
    fun `the extension is only read when nothing else says`() {
        assertThat(requestType(null, null, "https://example.com/style.css")).isEqualTo(RequestType.CSS)
        assertThat(requestType(null, null, "https://example.com/app.mjs")).isEqualTo(RequestType.JS)
        assertThat(requestType(null, null, "https://example.com/logo.svg")).isEqualTo(RequestType.IMAGES)
        assertThat(requestType(null, null, "https://example.com/font.woff2")).isEqualTo(RequestType.FONTS)
        assertThat(requestType(null, null, "https://example.com/clip.mp4")).isEqualTo(RequestType.MEDIA)
    }

    @Test
    fun `the query of a URL is not mistaken for its extension`() {
        assertThat(requestType(null, null, "https://example.com/main.css?v=2")).isEqualTo(RequestType.CSS)
    }

    @Test
    fun `an empty extension from a trailing dot matches nothing`() {
        assertThat(requestType(null, null, "https://example.com/page.")).isEqualTo(RequestType.OTHER)
    }

    @Test
    fun `what we cannot name is other`() {
        assertThat(requestType(null, null, "https://example.com/page")).isEqualTo(RequestType.OTHER)
        assertThat(requestType(null, "manifest", "https://example.com/manifest")).isEqualTo(RequestType.OTHER)
        assertThat(requestType(null, null, "")).isEqualTo(RequestType.OTHER)
        assertThat(requestType(null, null, "about:blank")).isEqualTo(RequestType.OTHER)
    }

    @Test
    fun `anything unrecognized does not throw`() {
        assertThat(requestType("", "", "")).isEqualTo(RequestType.OTHER)
        assertThat(requestType(";", null, "https://example.com/")).isEqualTo(RequestType.OTHER)
        assertThat(requestType(null, "  ", "   ")).isEqualTo(RequestType.OTHER)
    }

    @Test
    fun `an empty query keeps every URL`() {
        assertThat(urlMatchesQuery("https://example.com/api/items", "")).isTrue()
        assertThat(urlMatchesQuery("https://example.com/api/items", "   ")).isTrue()
    }

    @Test
    fun `a query matches anywhere in the URL, regardless of case`() {
        assertThat(urlMatchesQuery("https://example.com/api/items", "API/ITEMS")).isTrue()
        assertThat(urlMatchesQuery("https://example.com/api/items", "example.com")).isTrue()
        assertThat(urlMatchesQuery("https://example.com/api/items", "request")).isFalse()
    }

    @Test
    fun `a query is read without the spaces around it`() {
        assertThat(urlMatchesQuery("https://example.com/api/items", "  items  ")).isTrue()
    }
}
