package fulguris.network

import android.net.Uri
import android.webkit.WebResourceRequest
import fulguris.SDK_VERSION
import fulguris.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for our custom request headers helpers and for [CustomHeaderInterceptor.canIntercept].
 *
 * Robolectric is needed for [Uri], which [canIntercept] looks at through the requests it is given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class CustomHeadersTest {

    /**
     * A request good enough for [CustomHeaderInterceptor.canIntercept].
     */
    private class FakeRequest(
        url: String,
        private val method: String = "GET",
        private val isMainFrame: Boolean = false
    ) : WebResourceRequest {
        private val uri = Uri.parse(url)
        override fun getUrl() = uri
        override fun isForMainFrame() = isMainFrame
        override fun getRequestHeaders() = emptyMap<String, String>()
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
    }

    private val someHeaders = mapOf("X-Api-Key" to "secret")

    // Parse

    @Test
    fun `a name and a value are parsed`() {
        assertThat(parseCustomHeaders("X-Api-Key: secret"))
            .containsExactly(CustomHeader("X-Api-Key", "secret"))
    }

    @Test
    fun `empty input gives no header`() {
        assertThat(parseCustomHeaders("")).isEmpty()
    }

    @Test
    fun `blank lines and comments are skipped`() {
        val raw = """
            # our api

            X-Api-Key: secret
        """.trimIndent()

        assertThat(parseCustomHeaders(raw)).containsExactly(CustomHeader("X-Api-Key", "secret"))
    }

    @Test
    fun `a line without a colon is skipped`() {
        assertThat(parseCustomHeaders("not a header\nX-Api-Key: secret"))
            .containsExactly(CustomHeader("X-Api-Key", "secret"))
    }

    @Test
    fun `a line starting with a colon is skipped`() {
        assertThat(parseCustomHeaders(":secret")).isEmpty()
    }

    @Test
    fun `a header with an empty value is kept`() {
        assertThat(parseCustomHeaders("X-Empty:")).containsExactly(CustomHeader("X-Empty", ""))
    }

    @Test
    fun `a value keeps its colons`() {
        assertThat(parseCustomHeaders("Referer: https://example.com:8443/x"))
            .containsExactly(CustomHeader("Referer", "https://example.com:8443/x"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertThat(parseCustomHeaders("  X-Api-Key  :   secret  "))
            .containsExactly(CustomHeader("X-Api-Key", "secret"))
    }

    @Test
    fun `headers keep the order they were written in`() {
        assertThat(parseCustomHeaders("A: 1\nB: 2").map { it.name }).containsExactly("A", "B")
    }

    // Serialize

    @Test
    fun `one header per line`() {
        assertThat(serializeCustomHeaders(listOf(CustomHeader("A", "1"), CustomHeader("B", "2"))))
            .isEqualTo("A: 1\nB: 2")
    }

    @Test
    fun `serializing no header gives an empty string`() {
        assertThat(serializeCustomHeaders(emptyList())).isEmpty()
    }

    @Test
    fun `parsing what we serialized gives our headers back`() {
        val headers = listOf(CustomHeader("A", "1"), CustomHeader("B", "two three"))

        assertThat(parseCustomHeaders(serializeCustomHeaders(headers))).isEqualTo(headers)
    }

    // Map

    @Test
    fun `a name given twice keeps its last value`() {
        val headers = listOf(CustomHeader("A", "1"), CustomHeader("A", "2"))

        assertThat(customHeaderMap(headers)).containsExactly(entry("A", "2"))
    }

    @Test
    fun `the map keeps our order`() {
        val headers = listOf(CustomHeader("B", "2"), CustomHeader("A", "1"))

        assertThat(customHeaderMap(headers).keys).containsExactly("B", "A")
    }

    // Merge

    @Test
    fun `custom headers are added to the base ones`() {
        val merged = mergeHeaders(mapOf("Accept" to "text/html"), mapOf("X-Api-Key" to "secret"))

        assertThat(merged).containsExactly(entry("Accept", "text/html"), entry("X-Api-Key", "secret"))
    }

    @Test
    fun `a custom header replaces its namesake whatever its case`() {
        val merged = mergeHeaders(mapOf("Authorization" to "old"), mapOf("authorization" to "new"))

        assertThat(merged).containsExactly(entry("authorization", "new"))
    }

    @Test
    fun `merging leaves the base headers untouched`() {
        val base = mutableMapOf("Accept" to "text/html")

        mergeHeaders(base, mapOf("Accept" to "json"))

        assertThat(base).containsExactly(entry("Accept", "text/html"))
    }

    // Interception

    @Test
    fun `a subresource of an http domain is intercepted`() {
        assertThat(CustomHeaderInterceptor.canIntercept(FakeRequest("https://example.com/app.js"), someHeaders)).isTrue()
    }

    @Test
    fun `a domain without custom headers is left alone`() {
        assertThat(CustomHeaderInterceptor.canIntercept(FakeRequest("https://example.com/app.js"), emptyMap())).isFalse()
    }

    @Test
    fun `the main document is left to loadUrl`() {
        val request = FakeRequest("https://example.com/", isMainFrame = true)

        assertThat(CustomHeaderInterceptor.canIntercept(request, someHeaders)).isFalse()
    }

    @Test
    fun `a non http url is left alone`() {
        assertThat(CustomHeaderInterceptor.canIntercept(FakeRequest("data:text/plain,hi"), someHeaders)).isFalse()
    }

    @Test
    fun `a bodiless method is intercepted`() {
        listOf("GET", "HEAD", "OPTIONS", "DELETE").forEach { method ->
            assertThat(CustomHeaderInterceptor.canIntercept(FakeRequest("https://example.com/x", method), someHeaders))
                .describedAs(method)
                .isTrue()
        }
    }

    @Test
    fun `a lowercase method is recognised`() {
        assertThat(CustomHeaderInterceptor.canIntercept(FakeRequest("https://example.com/x", "get"), someHeaders)).isTrue()
    }

    @Test
    fun `a request with a body is left to WebView`() {
        listOf("POST", "PUT", "PATCH").forEach { method ->
            assertThat(CustomHeaderInterceptor.canIntercept(FakeRequest("https://example.com/x", method), someHeaders))
                .describedAs(method)
                .isFalse()
        }
    }
}
