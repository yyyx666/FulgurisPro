package fulguris.html.history

import com.anthonycr.mezzanine.FileStream

/**
 * The store for the history HTML page.
 */
@FileStream("src/main/html/history.html")
interface HistoryPageReader {

    fun provideHtml(): String

}
