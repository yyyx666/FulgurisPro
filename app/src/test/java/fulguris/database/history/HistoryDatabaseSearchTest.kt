package fulguris.database.history

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import fulguris.SDK_VERSION
import fulguris.TestApplication
import fulguris.database.HistoryEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for the history search and recent queries.
 *
 * We rely on Robolectric for its working SQLite implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class HistoryDatabaseSearchTest {

    private lateinit var database: HistoryDatabase

    @Before
    fun setUp() {
        database = HistoryDatabase(RuntimeEnvironment.application)
        // Start each test from an empty table
        database.deleteHistory().blockingAwait()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun visit(url: String, title: String) {
        database.visitHistoryEntry(url, title).blockingAwait()
    }

    /**
     * Fill our history with [count] entries, all matching the `needle` token.
     */
    private fun visitMany(count: Int, needle: String = "needle") {
        repeat(count) { index ->
            visit("https://$needle$index.example.com", "Page $needle $index")
        }
    }

    @Test
    fun `search matches the title`() {
        visit("https://kotlinlang.org", "Kotlin Programming Language")
        visit("https://example.com", "Example Domain")

        val results = database.searchHistoryEntries("Kotlin", 200).blockingGet()

        assertThat(results).hasSize(1)
        assertThat(results[0].url).isEqualTo("https://kotlinlang.org")
    }

    @Test
    fun `search matches the url`() {
        visit("https://kotlinlang.org", "Kotlin Programming Language")
        visit("https://example.com", "Example Domain")

        val results = database.searchHistoryEntries("kotlinlang", 200).blockingGet()

        assertThat(results).hasSize(1)
        assertThat(results[0].title).isEqualTo("Kotlin Programming Language")
    }

    @Test
    fun `search is a substring match and returns every matching entry`() {
        visitMany(3)
        visit("https://example.com", "Example Domain")

        val results = database.searchHistoryEntries("needle", 200).blockingGet()

        assertThat(results).hasSize(3)
    }

    @Test
    fun `search returns nothing when nothing matches`() {
        visitMany(3)

        assertThat(database.searchHistoryEntries("absent", 200).blockingGet()).isEmpty()
    }

    @Test
    fun `search honours the requested limit`() {
        visitMany(10)

        assertThat(database.searchHistoryEntries("needle", 4).blockingGet()).hasSize(4)
    }

    @Test
    fun `search returns the most recently visited first`() {
        val older = HistoryEntry(url = "https://older.example.com", title = "older page", lastTimeVisited = 1_000L)
        val newer = HistoryEntry(url = "https://newer.example.com", title = "newer page", lastTimeVisited = 2_000L)
        insert(older)
        insert(newer)

        val results = database.searchHistoryEntries("er page", 200).blockingGet()

        assertThat(results.map { it.url }).containsExactly(newer.url, older.url)
    }

    @Test
    fun `recent entries are most recently visited first`() {
        val older = HistoryEntry(url = "https://older.example.com", title = "older page", lastTimeVisited = 1_000L)
        val newer = HistoryEntry(url = "https://newer.example.com", title = "newer page", lastTimeVisited = 2_000L)
        insert(older)
        insert(newer)

        val results = database.recentHistoryEntries(200).blockingGet()

        assertThat(results.map { it.url }).containsExactly(newer.url, older.url)
    }

    @Test
    fun `recent entries honours the requested limit`() {
        visitMany(10)

        assertThat(database.recentHistoryEntries(3).blockingGet()).hasSize(3)
    }

    @Test
    fun `recent entries can return more than the last hundred visited`() {
        visitMany(120)

        // That is what our history page asks for, it used to be capped at a hundred
        assertThat(database.recentHistoryEntries(500).blockingGet()).hasSize(120)
        assertThat(database.lastHundredVisitedHistoryEntries().blockingGet()).hasSize(100)
    }

    /**
     * Insert an entry with a controlled time visited, [HistoryDatabase.visitHistoryEntry] always
     * stamps the current time.
     */
    private fun insert(entry: HistoryEntry) {
        database.writableDatabase.insertWithOnConflict(
            HISTORY_TABLE,
            null,
            ContentValues().apply {
                put("url", entry.url)
                put("title", entry.title)
                put("time", entry.lastTimeVisited)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private companion object {
        const val HISTORY_TABLE = "history"
    }
}
