package fulguris.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for the optional fields of [fulguris.view.WebPageClient.PageRequest].
 */
class PageRequestTest {

    private fun request() = WebPageClient.PageRequest(url = "https://example.com", wasBlocked = false)

    @Test
    fun `a request is not blocked by default and is not a main frame`() {
        val request = request()

        assertThat(request.wasBlocked).isFalse()
        assertThat(request.isMainFrame).isFalse()
    }

    @Test
    fun `request side fields default to empty values`() {
        val request = request()

        assertThat(request.method).isEmpty()
        assertThat(request.requestHeaders).isEmpty()
    }

    @Test
    fun `response side fields default to unknown`() {
        val request = request()

        // Those are only known when a network engine handled the request, see WebPageClient
        assertThat(request.resourceType).isNull()
        assertThat(request.statusCode).isNull()
        assertThat(request.statusText).isNull()
        assertThat(request.mimeType).isNull()
        assertThat(request.durationMs).isNull()
        // Only a request that never got an answer carries one, as our page-side observer saw it
        assertThat(request.error).isNull()
        // Only a WebSocket row carries one, and only once our page-side observer reported it
        assertThat(request.webSocketState).isNull()
    }

    @Test
    fun `body fields default to absent, for the response and the request alike`() {
        val request = request()

        assertThat(request.bodyId).isNull()
        assertThat(request.bodySize).isNull()
        assertThat(request.bodyTruncated).isFalse()
        // Only our page-side fetch/XHR observer reports a request body, see recordNetworkReport
        assertThat(request.requestBodyId).isNull()
        assertThat(request.requestBodySize).isNull()
        assertThat(request.requestBodyTruncated).isFalse()
    }
}
