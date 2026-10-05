package fulguris.html.history

import fulguris.database.HistoryEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Calendar

/**
 * Tests for [groupHistoryEntriesByDay] and [historyDayStart].
 *
 * Those are pure functions, we thus expect a plain JVM test here: nothing may pull Android in.
 */
class HistoryGroupingTest {

    /**
     * A timestamp for the provided day offset from today, at the provided time of day.
     */
    private fun timeOfDay(dayOffset: Int, hourOfDay: Int, minute: Int = 0): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, dayOffset)
        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun historyEntry(url: String, time: Long) =
        HistoryEntry(url = url, title = url, lastTimeVisited = time)

    @Test
    fun `historyDayStart returns midnight of the day containing that time`() {
        val afternoon = timeOfDay(dayOffset = 0, hourOfDay = 15, minute = 30)
        val midnight = timeOfDay(dayOffset = 0, hourOfDay = 0)

        assertThat(historyDayStart(afternoon)).isEqualTo(midnight)
    }

    @Test
    fun `historyDayStart is stable across a whole day`() {
        val morning = timeOfDay(dayOffset = 0, hourOfDay = 6)
        val night = timeOfDay(dayOffset = 0, hourOfDay = 23)

        assertThat(historyDayStart(morning)).isEqualTo(historyDayStart(night))
    }

    @Test
    fun `historyDayStart moves to the next day at midnight`() {
        val lateToday = timeOfDay(dayOffset = 0, hourOfDay = 23, minute = 59)
        val earlyTomorrow = timeOfDay(dayOffset = 1, hourOfDay = 0)

        assertThat(historyDayStart(earlyTomorrow)).isGreaterThan(historyDayStart(lateToday))
    }

    @Test
    fun `grouping an empty list yields no group`() {
        assertThat(groupHistoryEntriesByDay(emptyList())).isEmpty()
    }

    @Test
    fun `grouping a single entry yields a single group holding it`() {
        val entry = historyEntry("https://example.com", timeOfDay(0, 12))

        val groups = groupHistoryEntriesByDay(listOf(entry))

        assertThat(groups).hasSize(1)
        assertThat(groups[0].dayStart).isEqualTo(historyDayStart(entry.lastTimeVisited))
        assertThat(groups[0].entries).containsExactly(entry)
    }

    @Test
    fun `grouping by day preserves the most recent first order`() {
        val todayMorning = historyEntry("https://a.example.com", timeOfDay(0, 8))
        val todayEvening = historyEntry("https://b.example.com", timeOfDay(0, 20))
        val yesterday = historyEntry("https://c.example.com", timeOfDay(-1, 20))

        val groups = groupHistoryEntriesByDay(listOf(todayEvening, todayMorning, yesterday))

        assertThat(groups).hasSize(2)
        assertThat(groups[0].dayStart).isEqualTo(historyDayStart(todayEvening.lastTimeVisited))
        assertThat(groups[0].entries).containsExactly(todayEvening, todayMorning)
        assertThat(groups[1].dayStart).isEqualTo(historyDayStart(yesterday.lastTimeVisited))
        assertThat(groups[1].entries).containsExactly(yesterday)
    }

    @Test
    fun `grouping only merges consecutive entries of the same day`() {
        val today = historyEntry("https://a.example.com", timeOfDay(0, 20))
        val yesterday = historyEntry("https://b.example.com", timeOfDay(-1, 20))
        val backToToday = historyEntry("https://c.example.com", timeOfDay(0, 10))

        val groups = groupHistoryEntriesByDay(listOf(today, yesterday, backToToday))

        // Our entries are expected to be sorted by time, we thus keep one group per run of days
        assertThat(groups).hasSize(3)
        assertThat(groups.map { it.entries.single() }).containsExactly(today, yesterday, backToToday)
    }
}
