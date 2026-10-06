package fulguris.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for [parseQueryParameters].
 *
 * The request details panel lists what this returns, so the repeating parameters a plain map would
 * swallow are what we pin down here.
 */
class QueryStringTest {

    @Test
    fun `a missing query yields no parameter`() {
        assertThat(parseQueryParameters(null)).isEmpty()
    }

    @Test
    fun `an empty query yields no parameter`() {
        assertThat(parseQueryParameters("")).isEmpty()
    }

    @Test
    fun `a single parameter is split into its name and value`() {
        assertThat(parseQueryParameters("page=1")).containsExactly("page" to "1")
    }

    @Test
    fun `every value of a repeated name is kept`() {
        assertThat(parseQueryParameters("tag=a&tag=b")).containsExactly("tag" to "a", "tag" to "b")
    }

    @Test
    fun `parameters sharing a value are all kept`() {
        assertThat(parseQueryParameters("page=1&size=1")).containsExactly("page" to "1", "size" to "1")
    }

    @Test
    fun `parameter order is preserved`() {
        assertThat(parseQueryParameters("b=2&a=1&c=3").map { it.first })
            .containsExactly("b", "a", "c")
    }

    @Test
    fun `a plus and a percent encoded space both decode to a space`() {
        assertThat(parseQueryParameters("q=a+b&r=a%20b")).containsExactly("q" to "a b", "r" to "a b")
    }

    @Test
    fun `percent encoded UTF-8 decodes to its characters`() {
        assertThat(parseQueryParameters("q=%E4%B8%AD")).containsExactly("q" to "中")
    }

    @Test
    fun `a parameter without an equals sign has an empty value`() {
        assertThat(parseQueryParameters("flag")).containsExactly("flag" to "")
    }

    @Test
    fun `everything after the first equals sign belongs to the value`() {
        assertThat(parseQueryParameters("token=a=b=c")).containsExactly("token" to "a=b=c")
    }

    @Test
    fun `an encoded equals sign is decoded into the value`() {
        assertThat(parseQueryParameters("token=a%3Db")).containsExactly("token" to "a=b")
    }

    @Test
    fun `an invalid escape is left as it was written`() {
        assertThat(parseQueryParameters("q=100%")).containsExactly("q" to "100%")
    }

    @Test
    fun `a trailing ampersand produces no empty parameter`() {
        assertThat(parseQueryParameters("page=1&")).containsExactly("page" to "1")
    }

    @Test
    fun `an empty parameter between two others is dropped`() {
        assertThat(parseQueryParameters("page=1&&size=2")).containsExactly("page" to "1", "size" to "2")
    }
}
