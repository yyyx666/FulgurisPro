package fulguris.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for [parseCookieFields] and [buildSetCookie], the two halves of our cookie editor.
 */
class CookieFieldsTest {

    @Test
    fun `a plain legacy cookie only has a name and a value`() {
        val fields = parseCookieFields("session=abc123")

        assertThat(fields.name).isEqualTo("session")
        assertThat(fields.value).isEqualTo("abc123")
        assertThat(fields.domain).isNull()
        assertThat(fields.path).isNull()
    }

    @Test
    fun `domain and path attributes are parsed out`() {
        val fields = parseCookieFields("session=abc123; Domain=example.com; Path=/app; Secure")

        assertThat(fields.name).isEqualTo("session")
        assertThat(fields.value).isEqualTo("abc123")
        assertThat(fields.domain).isEqualTo("example.com")
        assertThat(fields.path).isEqualTo("/app")
    }

    @Test
    fun `attributes are matched case insensitively`() {
        val fields = parseCookieFields("session=abc123; domain=example.com; PATH=/app")

        assertThat(fields.domain).isEqualTo("example.com")
        assertThat(fields.path).isEqualTo("/app")
    }

    @Test
    fun `a value containing an equals sign is kept whole`() {
        val fields = parseCookieFields("token=a=b=c")

        assertThat(fields.name).isEqualTo("token")
        assertThat(fields.value).isEqualTo("a=b=c")
    }

    @Test
    fun `an empty value is kept as an empty string`() {
        val fields = parseCookieFields("emptied=")

        assertThat(fields.name).isEqualTo("emptied")
        assertThat(fields.value).isEmpty()
    }

    @Test
    fun `writing a cookie without attributes only writes name and value`() {
        val cookie = buildSetCookie(CookieFields(name = "session", value = "abc123"))

        assertThat(cookie).isEqualTo("session=abc123")
    }

    @Test
    fun `writing a cookie includes the attributes we know about`() {
        val cookie = buildSetCookie(
            CookieFields(name = "session", value = "abc123", domain = "example.com", path = "/app")
        )

        assertThat(cookie).isEqualTo("session=abc123;Domain=example.com;Path=/app")
    }

    @Test
    fun `empty attributes are left out`() {
        val cookie = buildSetCookie(
            CookieFields(name = "session", value = "abc123", domain = "", path = "")
        )

        assertThat(cookie).isEqualTo("session=abc123")
    }

    @Test
    fun `deleting a cookie expires it instead of writing its value`() {
        val cookie = buildSetCookie(
            CookieFields(name = "session", value = "abc123", domain = "example.com", path = "/app"),
            delete = true
        )

        assertThat(cookie).isEqualTo("session=;Domain=example.com;Path=/app;Max-Age=0")
    }

    @Test
    fun `a parsed cookie round trips back to a writable cookie`() {
        val original = "session=abc123; Domain=example.com; Path=/app"

        val written = buildSetCookie(parseCookieFields(original))

        assertThat(written).isEqualTo("session=abc123;Domain=example.com;Path=/app")
    }
}
