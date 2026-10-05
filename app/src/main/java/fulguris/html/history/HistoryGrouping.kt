package fulguris.html.history

import fulguris.database.HistoryEntry
import java.util.Calendar

/**
 * One day's worth of history entries, most recent first.
 */
data class HistoryGroup(val dayStart: Long, val entries: List<HistoryEntry>)

/**
 * Returns the start of the local day (midnight) containing [timeMillis].
 */
fun historyDayStart(timeMillis: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timeMillis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/**
 * Groups [entries] by calendar day, preserving the given order (expected most recent first).
 */
fun groupHistoryEntriesByDay(entries: List<HistoryEntry>): List<HistoryGroup> {
    val groups = ArrayList<HistoryGroup>()
    var currentDay = 0L
    var current = ArrayList<HistoryEntry>()

    entries.forEach { entry ->
        val day = historyDayStart(entry.lastTimeVisited)
        if (current.isEmpty()) {
            currentDay = day
        } else if (day != currentDay) {
            groups.add(HistoryGroup(currentDay, current))
            current = ArrayList()
            currentDay = day
        }
        current.add(entry)
    }

    if (current.isNotEmpty()) {
        groups.add(HistoryGroup(currentDay, current))
    }

    return groups
}
