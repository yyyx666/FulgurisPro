package fulguris.html.history

import fulguris.App
import fulguris.R
import fulguris.activity.WebBrowserActivity
import fulguris.constant.FILE
import fulguris.database.HistoryEntry
import fulguris.database.history.HistoryRepository
import fulguris.di.DiskScheduler
import fulguris.extensions.isDarkTheme
import fulguris.extensions.safeUse
import fulguris.favicon.FaviconModel
import fulguris.favicon.toValidUri
import fulguris.html.HtmlPageFactory
import fulguris.utils.ThemeUtils
import fulguris.utils.htmlColor
import android.app.Application
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.core.net.toUri
import dagger.Reusable
import fulguris.html.jsoup.andBuild
import fulguris.html.jsoup.body
import fulguris.html.jsoup.clone
import fulguris.html.jsoup.id
import fulguris.html.jsoup.parse
import fulguris.html.jsoup.removeElement
import fulguris.html.jsoup.tag
import fulguris.html.jsoup.title
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.Scheduler
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import javax.inject.Inject

/**
 * Factory for the history page.
 */
@Reusable
class HistoryPageFactory @Inject constructor(
    private val listPageReader: HistoryPageReader,
    private val application: Application,
    private val historyRepository: HistoryRepository,
    private val faviconModel: FaviconModel,
    @DiskScheduler private val diskScheduler: Scheduler
) : HtmlPageFactory {

    private val title = application.getString(R.string.action_history)
    private val defaultIconFile by lazy { File(application.cacheDir, DEFAULT_ICON) }

    override fun buildPage(): Single<String> = historyRepository
        .recentHistoryEntries(HISTORY_LIMIT)
        .map { list ->
            val onDark = (App.currentContext() as? WebBrowserActivity)?.isDarkTheme() == true
            val groups = groupHistoryEntriesByDay(list)

            parse(listPageReader.provideHtml()
                    // Show localized page title
                    .replace("\${pageTitle}", application.getString(R.string.action_history))
                    // Search box placeholder
                    .replace("\${searchHint}", application.getString(R.string.history_search_hint))
                    // Theme our page first
                    .replace("\${backgroundColor}", htmlColor(ThemeUtils.getSurfaceColor(App.currentContext())))
                    .replace("\${textColor}", htmlColor(ThemeUtils.getColor(App.currentContext(),R.attr.colorOnSurface)))
                    .replace("\${secondaryTextColor}", htmlColor(ThemeUtils.getColor(App.currentContext(),R.attr.colorSecondary)))
                    .replace("\${dividerColor}", htmlColor(ThemeUtils.getColor(App.currentContext(),R.attr.colorOutline)))
            ) andBuild {
                title { title }
                body {
                    val repeatedElement = id("repeated").removeElement()
                    val groupHeaderElement = id("groupHeader").removeElement()
                    id("content") {
                        groups.forEach { group ->
                            appendChild(groupHeaderElement.clone {
                                id("groupTitle") { text(dayLabel(group.dayStart)) }
                            })
                            group.entries.forEach { entry ->
                                appendChild(repeatedElement.clone {
                                    tag("a") { attr("href", entry.url) }
                                    tag("img") { attr("src", faviconFileFor(entry, onDark).toString()) }
                                    id("title") { text(entry.title) }
                                    id("url") { text(entry.url) }
                                })
                            }
                        }
                    }
                }
            }
        }
        .map { content -> Pair(createHistoryPage(), content) }
        .doOnSuccess { (page, content) ->
            FileWriter(page, false).use { it.write(content) }
        }
        .map { (page, _) -> "$FILE$page" }

    /**
     * Returns a favicon cache file path we can reference directly from the page `<img src>`.
     * Seeds a default letter-avatar icon when the URL has never been visited with a favicon.
     * Mirrors [fulguris.html.bookmark.BookmarkPageFactory].
     */
    private fun faviconFileFor(entry: HistoryEntry, onDark: Boolean): File {
        val uri = entry.url.toUri().toValidUri()
        if (uri == null) {
            ensureDefaultIcon()
            return defaultIconFile
        }

        val lightFile = FaviconModel.getFaviconCacheFile(application, uri, false)
        if (!lightFile.exists()) {
            faviconModel.cacheFaviconForUrl(faviconModel.createDefaultBitmapForTitle(entry.title), entry.url)
                .subscribeOn(diskScheduler)
                .subscribe()
        }

        val darkFile = FaviconModel.getFaviconCacheFile(application, uri, true)
        return if (onDark && darkFile.exists()) darkFile else lightFile
    }

    private fun ensureDefaultIcon() {
        if (defaultIconFile.exists()) {
            return
        }
        val bitmap = faviconModel.createDefaultBitmapForTitle(null)
        FileOutputStream(defaultIconFile).safeUse { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
    }

    /**
     * Human readable label for a given day, e.g. "Today", "Yesterday" or a formatted date.
     */
    private fun dayLabel(dayStart: Long): String {
        val todayStart = historyDayStart(System.currentTimeMillis())
        return when (dayStart) {
            todayStart -> application.getString(R.string.history_group_today)
            historyDayStart(todayStart - 1) -> application.getString(R.string.history_group_yesterday)
            else -> DateUtils.formatDateTime(
                application,
                dayStart,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
            )
        }
    }

    /**
     * Use this observable to immediately delete the history page. This will clear the cached
     * history page that was stored on file.
     *
     * @return a completable that deletes the history page when subscribed to.
     */
    fun deleteHistoryPage(): Completable = Completable.fromAction {
        with(createHistoryPage()) {
            if (exists()) {
                delete()
            }
        }
    }

    private fun createHistoryPage() = File(application.filesDir, FILENAME)

    companion object {
        const val FILENAME = "history.html"

        /**
         * How many of the most recent entries we load into the page. Client side search filters
         * this rendered set, so we keep it comfortably above the 100 used for suggestions.
         */
        private const val HISTORY_LIMIT = 500

        private const val DEFAULT_ICON = "history-default.png"
    }

}
