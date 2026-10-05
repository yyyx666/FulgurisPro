package fulguris.network

import android.webkit.CookieManager
import fulguris.SDK_VERSION
import fulguris.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the cookies our engines send with the requests they issue themselves, and for how they
 * follow redirects.
 *
 * Robolectric is needed for [CookieManager].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class NetworkCookiesTest {

    private val engine = NetworkEngineHttpUrlConnection()

    // Cookies

    @Test
    fun `a host we hold no cookie for gives nothing`() {
        assertThat(engine.cookiesFor("https://example.com/")).isNull()
    }

    @Test
    fun `the cookies we hold are given back`() {
        CookieManager.getInstance().setCookie("https://example.com/", "session=abc")

        assertThat(engine.cookiesFor("https://example.com/")).isEqualTo("session=abc")
    }

    @Test
    fun `our cookies do not leak to another host`() {
        CookieManager.getInstance().setCookie("https://example.com/", "session=abc")

        assertThat(engine.cookiesFor("https://other.com/")).isNull()
    }

    // Redirects

    @Test
    fun `a relative location resolves against the url it came from`() {
        assertThat(absoluteUrl("https://example.com/a/b", "../c"))
            .isEqualTo("https://example.com/c")
    }

    @Test
    fun `a root relative location keeps the host`() {
        assertThat(absoluteUrl("https://example.com/a/b", "/c"))
            .isEqualTo("https://example.com/c")
    }

    @Test
    fun `an absolute location is kept`() {
        assertThat(absoluteUrl("http://example.com/", "https://example.com/x"))
            .isEqualTo("https://example.com/x")
    }

    @Test
    fun `a host relative location keeps the scheme`() {
        assertThat(absoluteUrl("https://example.com/", "//other.com/x"))
            .isEqualTo("https://other.com/x")
    }

    @Test
    fun `a location that is no http url is not followed`() {
        assertThat(absoluteUrl("https://example.com/", "mailto:someone@example.com")).isNull()
        assertThat(absoluteUrl("https://example.com/", "javascript:void(0)")).isNull()
    }
}
