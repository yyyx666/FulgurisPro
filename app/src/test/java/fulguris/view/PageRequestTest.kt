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
        assertThat(request.mimeType).isNull()
        assertThat(request.durationMs).isNull()
    }
}
