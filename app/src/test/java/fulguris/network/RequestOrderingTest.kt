package fulguris.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for [groupNewestFirstBy].
 *
 * That ordering is what the requests panel shows, both its domain sections and the rows within
 * them, so we pin it down here rather than on a device.
 */
class RequestOrderingTest {

    private data class Item(val name: String, val time: Long, val group: String = "example.com")

    private fun grouped(vararg items: Item) =
        groupNewestFirstBy(items.toList(), { it.time }, { it.group })

    @Test
    fun `grouping an empty list yields no group`() {
        assertThat(grouped()).isEmpty()
    }

    @Test
    fun `items within a group are ordered newest first`() {
        val oldest = Item("oldest", 1)
        val newest = Item("newest", 3)
        val middle = Item("middle", 2)

        assertThat(grouped(oldest, newest, middle)["example.com"]!!.map { it.name })
            .containsExactly("newest", "middle", "oldest")
    }

    @Test
    fun `groups are ordered by their most recent item`() {
        val older = Item("older", 1, group = "a.com")
        val newer = Item("newer", 2, group = "b.com")
        val newest = Item("newest", 3, group = "c.com")

        assertThat(grouped(newer, older, newest).keys)
            .containsExactly("c.com", "b.com", "a.com")
    }

    @Test
    fun `a group with one recent item comes before a group with many older ones`() {
        val many = (1L..5L).map { Item("bulk$it", it, group = "busy.com") }
        val one = Item("single", 100, group = "quiet.com")

        assertThat(grouped(*(many + one).toTypedArray()).keys)
            .containsExactly("quiet.com", "busy.com")
    }

    @Test
    fun `items sharing a timestamp keep their original order`() {
        val first = Item("first", 7)
        val second = Item("second", 7)

        assertThat(grouped(first, second)["example.com"]!!.map { it.name })
            .containsExactly("first", "second")
    }
}
