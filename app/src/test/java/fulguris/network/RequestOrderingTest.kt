package fulguris.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Tests for [newestFirst].
 *
 * That ordering is the requests panel, so we pin it down here rather than on a device.
 */
class RequestOrderingTest {

    private data class Item(val name: String, val time: Long)

    private fun ordered(vararg items: Item) =
        newestFirst(items.toList(), { it.time }).map { it.name }

    @Test
    fun `an empty list stays empty`() {
        assertThat(newestFirst(emptyList<Item>(), { it.time })).isEmpty()
    }

    @Test
    fun `items come out newest first`() {
        val oldest = Item("oldest", 1)
        val middle = Item("middle", 2)
        val newest = Item("newest", 3)

        assertThat(ordered(oldest, newest, middle)).containsExactly("newest", "middle", "oldest")
    }

    @Test
    fun `items of different domains interleave by time instead of grouping`() {
        val page = Item("page", 1)
        val script = Item("script", 2)
        val image = Item("image", 3)

        assertThat(ordered(script, page, image)).containsExactly("image", "script", "page")
    }

    @Test
    fun `items sharing a timestamp keep their original order`() {
        val first = Item("first", 7)
        val second = Item("second", 7)
        val third = Item("third", 7)

        assertThat(ordered(first, second, third)).containsExactly("first", "second", "third")
    }

    @Test
    fun `the list we are given is left untouched`() {
        val oldest = Item("oldest", 1)
        val newest = Item("newest", 2)
        val items = mutableListOf(oldest, newest)

        newestFirst(items, { it.time })

        assertThat(items).containsExactly(oldest, newest)
    }
}
