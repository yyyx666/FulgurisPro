/*
 * The contents of this file are subject to the Common Public Attribution License Version 1.0.
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * https://github.com/Slion/Fulguris/blob/main/LICENSE.CPAL-1.0.
 * The License is based on the Mozilla Public License Version 1.1, but Sections 14 and 15 have been
 * added to cover use of software over a computer network and provide for limited attribution for
 * the Original Developer. In addition, Exhibit A has been modified to be consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an "AS IS" basis, WITHOUT WARRANTY OF
 * ANY KIND, either express or implied. See the License for the specific language governing rights
 * and limitations under the License.
 *
 * The Original Code is Fulguris.
 *
 * The Original Developer is the Initial Developer.
 *
 * The Initial Developer of the Original Code is Stéphane Lenclud.
 *
 * All portions of the code written by Stéphane Lenclud are Copyright © 2020 Stéphane Lenclud.
 * All Rights Reserved.
 */

package fulguris.settings.fragment

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import fulguris.R
import fulguris.activity.WebBrowserActivity
import fulguris.extensions.launch
import fulguris.extensions.onConfigurationChange
import fulguris.extensions.toast
import fulguris.settings.CacheRequests
import fulguris.settings.IdbRecord
import fulguris.settings.IdbRecords
import fulguris.settings.IdbStore
import fulguris.settings.IdbStores
import fulguris.settings.IdbValue
import fulguris.settings.LevelResult
import fulguris.settings.StorageDump
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber

/**
 * Storage inspector for the current page.
 *
 * Cookies are not enumerable through the platform API, we simply link to our dedicated
 * [CookiesSettingsFragment] for those. Everything else can only be read and written from inside the
 * page itself, thus we go through [WebView.evaluateJavascript].
 *
 * What the page stores is read **one level at a time**: the panel itself only asks for the web
 * storages, the database and cache names, see [PANEL_SCRIPT]. What is inside a database or a cache
 * is read when the user opens it, see [storesScript], [recordsScript] and [requestsScript]. Reading
 * all of it up front is what made this panel crawl on pages holding a lot of data, and each of those
 * scripts only reports what it was asked for.
 *
 * The records of an object store get one more cut: they are shown as a table, with a column per
 * field, and read one page at a time, see [RECORDS_PAGE_SIZE]. Neither what is on screen nor what
 * comes over the bridge then depends on how much the store holds.
 *
 * Every script here is asynchronous except the ones writing to the web storages: they stash their
 * result on a page global and we poll that global until it shows up, see [runAsync]. Each level
 * opens with a spinner rather than once its data is in, see [Level].
 *
 * The CacheStorage is read only, the Cache API has no upsert we could offer here.
 */
@AndroidEntryPoint
class StorageFragment : AbstractSettingsFragment() {

    /**
     * The two web storages we can enumerate, along with their page side accessor.
     */
    private enum class Target(val accessor: String) {
        Local("localStorage"),
        Session("sessionStorage")
    }

    /**
     * Polling back our page globals happens through this.
     */
    private val pollHandler = Handler(Looper.getMainLooper())

    /**
     * Every poll we have in flight, so that we can drop them all when we leave this screen.
     */
    private val polls = mutableListOf<Poll>()

    /**
     * Every drill-down dialog we have open, so that we can close them along with us.
     */
    private val levels = mutableListOf<Level>()

    override fun titleResourceId(): Int = R.string.pref_title_storage

    override fun providePreferencesXmlResource() = R.xml.preference_storage

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        clickablePreference(preference = getString(R.string.pref_key_storage_refresh)) {
            refresh()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        // Ours are wired to this view: the ones waiting for the page are not worth leaving behind
        dismissLevels()
        cancelPolls()
        super.onDestroyView()
    }

    /**
     * Ask the current page for the storage it declares and display the result.
     *
     * Only names and counts come back, so this stays cheap however much the page holds.
     */
    private fun refresh() {
        val webView = currentWebView()
        if (webView == null) {
            render(null)
            return
        }

        renderLoading()
        runAsync(PANEL_SCRIPT, PANEL_VARIABLE, PANEL_TIMEOUT_MS) { payload ->
            render(StorageDump.parse(payload))
        }
    }

    /**
     * The WebView of the tab we are inspecting, null when there is none to inspect.
     */
    private fun currentWebView(): WebView? =
        (activity as? WebBrowserActivity)?.tabsManager?.currentTab?.webView

    /**
     * Run one of our page scripts, whose report we read back from [variable], and hand its payload
     * over to [onPayload] once it shows up.
     *
     * A null payload means we waited long enough and it never came, the caller then says why there
     * is nothing to show.
     */
    private fun runAsync(
        script: String,
        variable: String,
        timeoutMs: Long,
        onPayload: (String?) -> Unit
    ): Poll? {
        val webView = currentWebView()
        if (webView == null) {
            onPayload(null)
            return null
        }

        // Drop whatever a previous run left there, so that a stale value is never taken for a fresh one
        webView.evaluateJavascript(clearScript(variable), null)
        webView.evaluateJavascript(script, null)

        return Poll(webView, variable, (timeoutMs / POLL_INTERVAL_MS).toInt(), onPayload).also { it.start() }
    }

    private fun cancelPolls() {
        polls.toList().forEach { it.stop() }
    }

    private fun dismissLevels() {
        levels.toList().forEach { it.dismiss() }
    }

    /**
     * Decode the JSON string our page scripts returned.
     *
     * Note that [WebView.evaluateJavascript] hands us the JSON representation of our JavaScript
     * value, hence the double decoding. Anything else than a string, `null` included, yields null.
     */
    private fun decodeString(result: String?): String? = try {
        JSONTokener(result ?: "").nextValue() as? String
    } catch (e: Exception) {
        null
    }

    /**
     * One of our four categories, by its key.
     */
    private fun category(keyResourceId: Int): PreferenceCategory? =
        findPreference<PreferenceCategory>(getString(keyResourceId))

    /**
     * Show our four categories as still loading, without any row under them.
     *
     * The page is asked right away, but reading what it holds can take a moment on a busy page and
     * we say so rather than showing a panel that looks empty.
     */
    private fun renderLoading() {
        listOf(
            R.string.pref_key_cat_storage_local,
            R.string.pref_key_cat_storage_session,
            R.string.pref_key_cat_storage_idb,
            R.string.pref_key_cat_storage_caches
        ).forEach { keyResourceId ->
            val category = category(keyResourceId) ?: return@forEach
            category.removeAll()
            category.isVisible = true
            category.summary = getString(R.string.pref_summary_loading)
        }
    }

    /**
     * Display the provided dump, or our unavailability notice when we have none.
     */
    private fun render(newDump: StorageDump?) {
        val local = category(R.string.pref_key_cat_storage_local) ?: return
        val session = category(R.string.pref_key_cat_storage_session) ?: return
        val idb = category(R.string.pref_key_cat_storage_idb) ?: return
        val caches = category(R.string.pref_key_cat_storage_caches) ?: return

        val back = findPreference<Preference>(getString(R.string.pref_key_back))

        if (newDump == null) {
            local.isVisible = false
            session.isVisible = false
            idb.isVisible = false
            caches.isVisible = false
            val hasPage = (activity as? WebBrowserActivity)?.tabsManager?.currentTab != null
            back?.summary = getString(
                if (hasPage) R.string.storage_unavailable else R.string.storage_no_page
            )
            return
        }

        local.isVisible = true
        session.isVisible = true
        idb.isVisible = true
        caches.isVisible = true

        val origin = newDump.origin.takeIf { it.isNotEmpty() && it != "null" }
            ?: getString(R.string.storage_origin_unknown)
        back?.summary = origin

        populate(local, Target.Local, newDump.local)
        populate(session, Target.Session, newDump.session)
        populateDatabases(idb, newDump)
        populateCaches(caches, newDump)
    }

    /**
     * Fill one of our web storage categories with the way into its items.
     *
     * The items themselves live in a dialog rather than under the category: a page holding thousands
     * of them would otherwise stall the panel the moment it opens.
     */
    private fun populate(category: PreferenceCategory, target: Target, items: Map<String, String>) {
        category.summary = quantity(R.plurals.pref_summary_storage_items, items.size)

        category.addPreference(actionPreference(R.string.storage_browse_items, R.drawable.ic_list) {
            showItemsDialog(target, items)
        })

        category.addPreference(actionPreference(R.string.storage_item_add, R.drawable.ic_add) {
            showItemDialog(target, null, null)
        })

        category.addPreference(actionPreference(R.string.storage_item_clear, R.drawable.ic_delete_forever) {
            confirmClear(target)
        })
    }

    /**
     * List the IndexedDB databases of the current origin, tapping one opens its object stores.
     *
     * Names only: what a database holds is read when it is opened.
     */
    private fun populateDatabases(category: PreferenceCategory, dump: StorageDump) {
        if (!dump.idbSupported) {
            category.summary = null
            category.addPreference(noticePreference(R.string.storage_idb_unsupported))
            return
        }

        category.summary = quantity(R.plurals.pref_summary_storage_databases, dump.databases.size)

        dump.databases.forEach { database ->
            category.addPreference(rowPreference(database, null) { showStoresDialog(database) })
        }

        if (dump.databases.isEmpty()) {
            category.addPreference(noticePreference(R.string.storage_idb_empty))
        }
    }

    /**
     * List the CacheStorage caches of the current origin, tapping one opens its requests.
     *
     * Names only, for the same reason as the databases above.
     */
    private fun populateCaches(category: PreferenceCategory, dump: StorageDump) {
        if (!dump.cachesSupported) {
            category.summary = null
            category.addPreference(noticePreference(R.string.storage_cache_unsupported))
            return
        }

        category.summary = quantity(R.plurals.pref_summary_storage_caches, dump.caches.size)

        dump.caches.forEach { cache ->
            category.addPreference(rowPreference(cache, null) { showRequestsDialog(cache) })
        }

        if (dump.caches.isEmpty()) {
            category.addPreference(noticePreference(R.string.storage_cache_empty))
        }
    }

    /**
     * One of our runtime rows: a title, a value and a click through to a dialog.
     */
    private fun rowPreference(title: String, summary: String?, onClick: () -> Unit): Preference =
        x.Preference(requireContext()).apply {
            this.title = title
            this.summary = summary
            isSingleLineTitle = true
            titleEllipsize = TextUtils.TruncateAt.MIDDLE
            isSingleLineSummary = true
            // Keys are told apart by either end, values read from their start: cut the tail of
            // what we are showing, the middle only of what names it. Same as the cookies panel.
            summaryEllipsize = TextUtils.TruncateAt.END
            isIconSpaceReserved = false
            // Long press copies the value, tapping opens its dialog
            isCopyingEnabled = true
            setOnPreferenceClickListener {
                onClick()
                true
            }
        }

    /**
     * A non selectable row saying why there is nothing to show.
     */
    private fun noticePreference(stringId: Int): Preference =
        x.Preference(requireContext()).apply {
            summary = getString(stringId)
            isIconSpaceReserved = false
            isSelectable = false
        }

    /**
     * One of our storage actions as a preference.
     */
    private fun actionPreference(titleId: Int, iconId: Int, onClick: () -> Unit): Preference =
        x.Preference(requireContext()).apply {
            title = getString(titleId)
            setIcon(iconId)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                onClick()
                true
            }
        }

    /**
     * One of our plurals for the provided count, as text.
     */
    private fun quantity(pluralId: Int, count: Int): String =
        resources.getQuantityString(pluralId, count, count)

    /**
     * A `name — count` label, mentioning how many we did not read when we only got some of them.
     */
    private fun label(name: String, pluralId: Int, total: Int, shown: Int): String {
        val suffix = if (shown >= total) {
            ""
        } else {
            " (${getString(R.string.storage_show_partial, shown, total)})"
        }
        return "$name — ${quantity(pluralId, total)}$suffix"
    }

    /**
     * List the items of one web storage, tapping one opens it for editing.
     *
     * Those were read along with the panel, so this opens without waiting on the page.
     */
    private fun showItemsDialog(target: Target, items: Map<String, String>) {
        val rows = items.map { (key, value) ->
            Row(key, value) { showItemDialog(target, key, value) }
        }
        showRowsDialog(
            title = quantity(R.plurals.pref_summary_storage_items, items.size),
            rows = rows,
            emptyTextId = R.string.storage_empty
        )
    }

    /**
     * Show rows we already have in a dialog of their own.
     */
    private fun showRowsDialog(title: String, rows: List<Row>, emptyTextId: Int) {
        val adapter = RowAdapter(requireContext())
        if (rows.isEmpty()) {
            adapter.setNotice(getString(emptyTextId))
        } else {
            adapter.setRows(rows)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setAdapter(adapter) { _, which -> adapter.click(which) }
            .setNegativeButton(R.string.action_cancel, null)
            .launch()
    }

    /**
     * Show the object stores of one database, tapping one opens its records.
     */
    private fun showStoresDialog(database: String) {
        Level(
            title = database,
            script = { storesScript(database) },
            variable = STORES_VARIABLE
        ) { payload, reload ->
            when (val result = IdbStores.parse(payload)) {
                null -> Outcome.Notice(readFailure())
                is LevelResult.Failed ->
                    Outcome.Notice(result.message.ifEmpty { readFailure() })

                is LevelResult.Ready -> {
                    val stores = result.value.stores
                    if (stores.isEmpty()) {
                        Outcome.Notice(getString(R.string.storage_idb_no_store))
                    } else {
                        Outcome.Rows(stores.map { store ->
                            // A store holding nothing still opens: that is where records are added
                            Row("${store.name} — ${quantity(R.plurals.pref_summary_storage_records, store.count)}") {
                                showRecordsDialog(database, store, reload)
                            }
                        })
                    }
                }
            }
        }.show()
    }

    /**
     * Show one page of the records of one object store as a table, tapping one opens it for editing.
     *
     * One page at a time, with the buttons to walk the store: a store holding thousands of records
     * opens on the first [RECORDS_PAGE_SIZE] of them, and reads the next page only when asked, see
     * [recordsScript].
     *
     * [onClosed] reloads the store list behind us, whose record counts have moved on.
     */
    private fun showRecordsDialog(database: String, store: IdbStore, onClosed: () -> Unit) {
        Level(
            title = store.name,
            script = { offset -> recordsScript(database, store.name, offset, RECORDS_PAGE_SIZE) },
            variable = RECORDS_VARIABLE,
            onClosed = onClosed,
            pageSize = RECORDS_PAGE_SIZE
        ) { payload, reload ->
            when (val result = IdbRecords.parse(payload)) {
                null -> Outcome.Notice(readFailure())
                is LevelResult.Failed ->
                    Outcome.Notice(result.message.ifEmpty { readFailure() })

                is LevelResult.Ready -> {
                    val records = result.value.records
                    val fields = result.value.fields
                    val wholeValues = hasValueColumn(records)
                    Outcome.Table(
                        columns = recordColumns(store, result.value),
                        cells = records.map { record -> recordCells(store, fields, record) },
                        // Adding is always on offer: an empty store would otherwise hold nothing to tap
                        action = Row(getString(R.string.storage_idb_add)) {
                            showRecordDialog(database, store, null, reload)
                        },
                        onClick = { row, column ->
                            val record = records.getOrNull(row)
                            if (record != null) {
                                showCellDialog(
                                    database,
                                    store,
                                    fields,
                                    wholeValues,
                                    record,
                                    column,
                                    reload
                                )
                            }
                        },
                        // A long press is a press on the record, whichever of its cells it landed on
                        onLongClick = { row ->
                            records.getOrNull(row)?.let { showRecordDialog(database, store, it, reload) }
                        },
                        title = pageLabel(store.name, result.value),
                        total = result.value.count
                    )
                }
            }
        }.show()
    }

    /**
     * True when the table shows a column for whole values.
     *
     * Only a record whose value is a plain object has fields to spread over columns of its own: on a
     * page holding any other kind of value, the table gives one column to the values themselves.
     * Which is a matter of the page rather than of one record, so that every row of it shows the same
     * columns.
     */
    private fun hasValueColumn(records: List<IdbRecord>): Boolean = records.any { it.cells == null }

    /**
     * The columns to head the records table with, in the order the payload met their fields.
     *
     * A store keeping its keys on the side gets a column for them, our table having nowhere else to
     * show what a record is under. A store holding values that are not plain objects gets a column
     * for the value itself: those have no fields of their own to spread over columns, so they are
     * shown whole, shortened to what a cell can hold. All of it is what the page reported about the
     * records it sent us, which is one page of them.
     */
    private fun recordColumns(store: IdbStore, records: IdbRecords): List<String> {
        val columns = mutableListOf<String>()
        if (store.keyPath == null) {
            columns.add(getString(R.string.storage_item_key))
        }
        if (hasValueColumn(records.records)) {
            columns.add(getString(R.string.storage_item_value))
        }
        return columns + records.fields
    }

    /**
     * One table row: one cell per column we drew, in the same order.
     *
     * A record is missing the cells of every column its value has no field for: that is an empty cell
     * under a column headed by another record's field.
     */
    private fun recordCells(store: IdbStore, fields: List<String>, record: IdbRecord): List<String> {
        val cells = mutableListOf<String>()
        if (store.keyPath == null) {
            cells.add(record.key)
        }
        if (record.cells == null) {
            cells.add(record.value)
        }
        fields.forEach { field -> cells.add(record.cells?.get(field).orEmpty()) }
        return cells
    }

    /**
     * A `name — count` label for one page of records, saying which of them we are showing when the
     * store holds more than one page of them.
     *
     * Not [label]: that one says how many we left unread, which on a store we page through would be
     * true of every page but the last, and would say nothing about which page this one is.
     */
    private fun pageLabel(name: String, records: IdbRecords): String {
        var label = "$name — ${quantity(R.plurals.pref_summary_storage_records, records.count)}"
        if (records.count > RECORDS_PAGE_SIZE && records.records.isNotEmpty()) {
            val first = records.offset + 1
            val last = records.offset + records.records.size
            label += " (${getString(R.string.storage_page_range, first, last)})"
        }
        return label
    }

    /**
     * Show the requests of one cache. The CacheStorage is read only for us.
     */
    private fun showRequestsDialog(cache: String) {
        Level(
            title = cache,
            script = { requestsScript(cache) },
            variable = REQUESTS_VARIABLE
        ) { payload, _ ->
            when (val result = CacheRequests.parse(payload)) {
                null -> Outcome.Notice(readFailure())
                is LevelResult.Failed ->
                    Outcome.Notice(result.message.ifEmpty { readFailure() })

                is LevelResult.Ready -> {
                    val urls = result.value.urls
                    if (urls.isEmpty()) {
                        Outcome.Notice(getString(R.string.storage_cache_no_request))
                    } else {
                        Outcome.Rows(
                            rows = urls.map { Row(it) },
                            title = label(
                                cache,
                                R.plurals.pref_summary_storage_requests,
                                result.value.count,
                                urls.size
                            )
                        )
                    }
                }
            }
        }.show()
    }

    /**
     * Why we have nothing to show for one of our levels.
     */
    private fun readFailure(): String = getString(R.string.storage_read_failed)

    /**
     * Open what one cell of the records table holds: the key of a record, one of its fields, or a
     * value as a whole.
     *
     * Which column we were tapped in is what says so, the columns being the key of a store keeping
     * its keys on the side, then the value as a whole for records whose value is not a plain object,
     * then one per field. A cell standing for nothing on the record it was tapped on -- a field
     * column over a value that has no fields, the value column over one that has them -- opens the
     * record itself, as a long press does.
     */
    private fun showCellDialog(
        database: String,
        store: IdbStore,
        fields: List<String>,
        wholeValues: Boolean,
        record: IdbRecord,
        column: Int,
        onWritten: () -> Unit
    ) {
        // How many columns come before the field ones: the key, then the whole values
        val keyColumn = if (store.keyPath == null) 1 else 0
        val before = keyColumn + if (wholeValues) 1 else 0

        when {
            // The key of a record: a store with a key path keeps it inside the value, and gives it
            // no column of its own
            store.keyPath == null && column < keyColumn -> showKeyDialog(database, store, record, onWritten)

            // The whole value of a record that is not a plain object: it has no fields to edit this
            // value by, and opens as it is
            wholeValues && column < before && record.cells == null ->
                showValueDialog(database, store, record, null, onWritten)

            // One field of one record
            column >= before -> {
                val field = fields.getOrNull(column - before)
                if (field == null || record.cells == null) {
                    // A column of a field that record's value has none of
                    showRecordDialog(database, store, record, onWritten)
                } else {
                    showValueDialog(database, store, record, field, onWritten)
                }
            }

            // The whole value column over a value that is an object of its own: the record is what
            // that cell stands for
            else -> showRecordDialog(database, store, record, onWritten)
        }
    }

    /**
     * Rename one record of a store keeping its keys on the side.
     *
     * The value is neither read nor written here: renaming a record moves it, see [keyScript]. That
     * is what lets a record too big to open be renamed still.
     */
    private fun showKeyDialog(
        database: String,
        store: IdbStore,
        record: IdbRecord,
        onWritten: () -> Unit
    ) {
        val layout = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_storage_item, null)
        val keyField = layout.findViewById<EditText>(R.id.storageItemKey)
        val valueField = layout.findViewById<EditText>(R.id.storageItemValue)

        // One key at a time is what this dialog is for
        valueField.visibility = View.GONE
        keyField.setText(record.key)
        keyField.hint = getString(R.string.storage_idb_record_key)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("${store.name} — ${getString(R.string.storage_item_key)}")
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.storage_item_save, null)
            .launch()

        // Our own listener, set after the dialog is launched, so that invalid input keeps it open
        val save = (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)
        save?.setOnClickListener {
            val key = keyField.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                activity?.toast(R.string.storage_invalid_key)
                return@setOnClickListener
            }
            if (!isValidJson(key)) {
                activity?.toast(R.string.storage_idb_invalid_key)
                return@setOnClickListener
            }

            runAsyncScript(keyScript(database, store.name, record.key, key), onWritten)
            dialog.dismiss()
        }

        // Discard it on screen rotation as it's broken anyway
        layout.onConfigurationChange { dialog.dismiss() }
    }

    /**
     * Edit one value of one record: the value of a field, or a value as a whole.
     *
     * The value itself is not what the cell we tapped held -- a cell carries what it can show of it,
     * see [MAX_CELL_CHARS] -- so it is read from the page while the dialog comes up, and the box says
     * it is waiting until it arrives. A value too long to edit here, or one the page could only give
     * us a placeholder for, is shown without being editable, see [showValueIn].
     *
     * An empty box saves as the absence of a field: that is how one is taken out of a record, a value
     * of one's own having to be written as JSON text, `""` among them.
     */
    private fun showValueDialog(
        database: String,
        store: IdbStore,
        record: IdbRecord,
        field: String?,
        onWritten: () -> Unit
    ) {
        val layout = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_storage_item, null)
        val keyField = layout.findViewById<EditText>(R.id.storageItemKey)
        val valueField = layout.findViewById<EditText>(R.id.storageItemValue)
        val notice = layout.findViewById<TextView>(R.id.storageItemNotice)

        // The key of this record is not what we are here to change, and a store with a key path
        // keeps it inside the value anyway
        keyField.visibility = View.GONE
        valueField.hint = getString(R.string.storage_idb_record_value)
        // Nothing is written before the value is read: the box says what we are waiting on
        valueField.isEnabled = false
        valueField.setText(R.string.loading)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                "${store.name} — ${field ?: getString(R.string.storage_item_value)}"
            )
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.storage_item_save, null)
            .launch()

        // Our own listener, set after the dialog is launched, so that invalid input keeps it open
        val save = (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)
        save?.isEnabled = false
        save?.setOnClickListener {
            val text = valueField.text?.toString().orEmpty()
            // A field is taken out of a record by emptying its box, a value of one's own is written
            // as the JSON text of what it should hold
            if ((field == null || text.isNotEmpty()) && !isValidJson(text)) {
                activity?.toast(R.string.storage_idb_invalid_value)
                return@setOnClickListener
            }

            if (field == null) {
                // A store without a key path keeps the key this record was read under
                writeRecord(
                    database,
                    store,
                    record.key,
                    record.key.takeIf { store.keyPath == null },
                    text,
                    onWritten
                )
            } else {
                runAsyncScript(
                    fieldScript(database, store.name, store.keyPath, record.key, field, text),
                    onWritten
                )
            }
            dialog.dismiss()
        }

        val poll = readValue(
            database,
            store,
            record,
            field,
            onValue = { value -> save?.isEnabled = showValueIn(valueField, notice, value) },
            onFailure = { message ->
                activity?.toast(message)
                dialog.dismiss()
            }
        )
        dialog.setOnDismissListener { poll?.stop() }
        // Discard it on screen rotation as it's broken anyway
        layout.onConfigurationChange { dialog.dismiss() }
    }

    /**
     * Create or edit one IndexedDB record, editing also allows deleting it.
     *
     * A record we are editing has its value read from the page while this dialog opens, the cell we
     * came from holding no more than a preview of it. Its key is ours to write right away: a rename
     * never has to read the value, see [keyScript], though saving this dialog writes both at once.
     *
     * A value too long to edit here, or one the page could only give us a placeholder for, is shown
     * without being editable, see [showValueIn]. Deleting the record stays on offer, that being the
     * one thing one can usefully do with a value this size.
     */
    private fun showRecordDialog(
        database: String,
        store: IdbStore,
        record: IdbRecord?,
        onWritten: () -> Unit
    ) {
        val layout = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_storage_item, null)
        val keyField = layout.findViewById<EditText>(R.id.storageItemKey)
        val valueField = layout.findViewById<EditText>(R.id.storageItemValue)
        val notice = layout.findViewById<TextView>(R.id.storageItemNotice)

        // A store with a key path keeps the key inside the value, the browser derives it from there
        val keyInValue = store.keyPath != null
        keyField.isEnabled = !keyInValue
        keyField.hint = when {
            keyInValue -> getString(R.string.storage_idb_key_path, store.keyPath)
            store.autoIncrement -> getString(R.string.storage_idb_record_key_optional)
            else -> getString(R.string.storage_idb_record_key)
        }
        keyField.setText(record?.key.orEmpty())

        valueField.hint = getString(R.string.storage_idb_record_value)
        if (record == null) {
            valueField.isEnabled = true
        } else {
            // Nothing is written before the value is read: the box says what we are waiting on
            valueField.isEnabled = false
            valueField.setText(R.string.loading)
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (record == null) R.string.storage_record_new_title
                else R.string.storage_record_edit_title
            )
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            // Our own listener, set after the dialog is launched, so that invalid input keeps it open
            .setPositiveButton(R.string.storage_item_save, null)

        if (record != null) {
            builder.setNeutralButton(R.string.storage_item_delete) { _, _ ->
                deleteRecord(database, store, record, onWritten)
            }
        }

        val dialog = builder.launch()
        // `launch` hands us our dialog as a plain Dialog, we need our AlertDialog back to reach
        // its buttons. Our listener is set now rather than at build time, so that invalid input
        // keeps the dialog open. See AdBlockSettingsFragment for the same pattern.
        val save = (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)
        // A record we are editing stays unwritable until we have its value back
        save?.isEnabled = record == null
        save?.setOnClickListener {
            val value = valueField.text?.toString().orEmpty()
            if (!isValidJson(value)) {
                activity?.toast(R.string.storage_idb_invalid_value)
                return@setOnClickListener
            }

            // A store without a key path holds its keys on the side, an empty one is only allowed
            // when the browser generates it for us
            val key = if (keyInValue) null else keyField.text?.toString()?.trim().orEmpty()
            if (!key.isNullOrEmpty() && !isValidJson(key)) {
                activity?.toast(R.string.storage_idb_invalid_key)
                return@setOnClickListener
            }
            if (!keyInValue && key.isNullOrEmpty() && !store.autoIncrement) {
                activity?.toast(R.string.storage_invalid_key)
                return@setOnClickListener
            }

            writeRecord(database, store, record?.key, key?.ifEmpty { null }, value, onWritten)
            dialog.dismiss()
        }

        val poll = if (record == null) {
            null
        } else {
            readValue(
                database,
                store,
                record,
                null,
                onValue = { value ->
                    // Holding no more than the beginning of a value, or a placeholder standing in
                    // for one, we have nothing of it that is ours to write back
                    save?.isEnabled = showValueIn(valueField, notice, value)
                },
                onFailure = { message ->
                    activity?.toast(message)
                    dialog.dismiss()
                }
            )
        }
        dialog.setOnDismissListener { poll?.stop() }
        // Discard it on screen rotation as it's broken anyway
        layout.onConfigurationChange { dialog.dismiss() }
    }

    /**
     * Read one value of one record, handing it over to [onValue] once the page answers.
     *
     * [onFailure] gets what to tell the user when there is no value to show: a record deleted
     * meanwhile, a page that took too long. Both run on the UI thread, see [runAsync].
     *
     * The [Poll] comes back for the caller to stop when it is done waiting for it, a dialog being
     * dismissed taking its read with it.
     */
    private fun readValue(
        database: String,
        store: IdbStore,
        record: IdbRecord,
        field: String?,
        onValue: (IdbValue) -> Unit,
        onFailure: (String) -> Unit
    ): Poll? = runAsync(
        valueScript(database, store.name, record.key, field),
        VALUE_VARIABLE,
        LEVEL_TIMEOUT_MS
    ) { payload ->
        if (!isAdded) {
            return@runAsync
        }

        when (val result = IdbValue.parse(payload)) {
            null -> onFailure(readFailure())
            is LevelResult.Failed -> onFailure(result.message.ifEmpty { readFailure() })
            is LevelResult.Ready -> onValue(result.value)
        }
    }

    /**
     * Show one value in a value box, and say why it cannot be saved when it cannot.
     *
     * Returns whether that value is ours to write back. A value we hold no more than the beginning of
     * cannot be: writing it back would lose its tail. Neither can one we could only be given a
     * placeholder for, see [IdbValue.lossy]. Both are shown, shortened to what a read-only line can
     * hold, with a notice in place of an editor.
     */
    private fun showValueIn(valueField: EditText, notice: TextView, value: IdbValue): Boolean {
        val oversized = value.size > VALUE_EDIT_MAX_CHARS
        val editable = !oversized && !value.lossy

        valueField.isEnabled = editable
        valueField.setText(if (oversized) value.text.take(MAX_VIEW_CHARS) else value.text)

        when {
            oversized -> {
                notice.visibility = View.VISIBLE
                notice.text = getString(R.string.storage_record_too_large, readableSize(value.size))
            }

            value.lossy -> {
                notice.visibility = View.VISIBLE
                notice.text = getString(R.string.storage_value_not_editable)
            }
        }

        return editable
    }

    /**
     * How big a value is, in the units one thinks in, rounded down.
     *
     * We count the characters of its JSON rather than its bytes, the two being the same order for
     * what this is for: telling the user roughly how big the record they cannot edit here is.
     */
    private fun readableSize(chars: Int): String = when {
        chars >= 1024 * 1024 -> "${chars / (1024 * 1024)} MB"
        chars >= 1024 -> "${chars / 1024} KB"
        else -> "$chars"
    }

    /**
     * Create or edit one storage item. Editing also allows renaming or deleting the item.
     */
    private fun showItemDialog(target: Target, key: String?, value: String?) {
        val layout = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_storage_item, null)
        val keyField = layout.findViewById<EditText>(R.id.storageItemKey)
        val valueField = layout.findViewById<EditText>(R.id.storageItemValue)

        keyField.setText(key.orEmpty())
        valueField.setText(value.orEmpty())

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (key == null) R.string.storage_item_new_title else R.string.storage_item_edit_title)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.storage_item_save) { _, _ ->
                val newKey = keyField.text?.toString()?.trim().orEmpty()
                if (newKey.isEmpty()) {
                    activity?.toast(R.string.storage_invalid_key)
                    return@setPositiveButton
                }
                // Renaming an item is a matter of setting the new one and removing the old one
                if (key != null && key != newKey) {
                    runScript(removeItemScript(target, key))
                }
                runScript(setItemScript(target, newKey, valueField.text?.toString().orEmpty()))
            }

        if (key != null) {
            builder.setNeutralButton(R.string.storage_item_delete) { _, _ ->
                runScript(removeItemScript(target, key))
            }
        }

        val dialog = builder.launch()
        // Discard it on screen rotation as it's broken anyway
        layout.onConfigurationChange { dialog.dismiss() }
    }

    /**
     * Ask for confirmation before wiping one of our storages.
     */
    private fun confirmClear(target: Target) {
        val label = getString(
            if (target == Target.Local) {
                R.string.pref_category_title_storage_local
            } else {
                R.string.pref_category_title_storage_session
            }
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage_clear_confirm_title)
            .setMessage(getString(R.string.storage_clear_confirm_message, label))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                runScript("try{window.${target.accessor}.clear();}catch(e){String(e)}")
            }
            .launch()
    }

    /**
     * Write one record, or delete it when no value is given, then let [onWritten] show the result.
     *
     * [existingKey] is the JSON text of the key the record is under, null when there is no record to
     * replace. [key] is the key to write it under, null when the store derives it from the value.
     */
    private fun writeRecord(
        database: String,
        store: IdbStore,
        existingKey: String?,
        key: String?,
        value: String?,
        onWritten: () -> Unit
    ) {
        // A record we are replacing is removed first: its key may have changed, and a store with a key
        // path writes the whole thing back anyway. Both happen in the same transaction, so a failure
        // leaves the store untouched.
        val deleteKey = when {
            existingKey == null -> null
            store.keyPath != null -> existingKey
            key != null && key != existingKey -> existingKey
            else -> null
        }

        runAsyncScript(recordScript(database, store.name, deleteKey, key, value), onWritten)
    }

    /**
     * Remove one record, whatever its store keys its records by.
     *
     * [writeRecord] is about replacing a record, and a store keeping its keys on the side replaces one
     * under the key it already has: that is no way to say a record should be gone. Here the key of the
     * record itself is what is deleted, and nothing is written.
     */
    private fun deleteRecord(
        database: String,
        store: IdbStore,
        record: IdbRecord,
        onWritten: () -> Unit
    ) {
        runAsyncScript(recordScript(database, store.name, record.key, null, null), onWritten)
    }

    /**
     * True when the provided text is something the page will parse as JSON.
     */
    private fun isValidJson(text: String?): Boolean = try {
        if (text.isNullOrEmpty()) false else JSONTokener(text).nextValue() != null
    } catch (e: Exception) {
        false
    }

    /**
     * Mutate the page storage and refresh our dump once done.
     * Our synchronous scripts report an error message, or an empty string on success.
     */
    private fun runScript(script: String) {
        val webView = currentWebView()
        if (webView == null) {
            return
        }

        webView.evaluateJavascript(script) { result ->
            // Our callback is invoked on the UI thread, but we may have been dismissed by then
            if (!isAdded) {
                return@evaluateJavascript
            }

            val error = decodeString(result)

            if (!error.isNullOrEmpty()) {
                Timber.w("Storage script failed: $error")
                activity?.toast(error)
            }

            refresh()
        }
    }

    /**
     * Run one of our asynchronous write scripts, report a failure to the user, then let [onWritten]
     * show the outcome.
     */
    private fun runAsyncScript(script: String, onWritten: () -> Unit) {
        runAsync(script, RESULT_VARIABLE, WRITE_TIMEOUT_MS) { payload ->
            val outcome = payload?.let {
                try {
                    JSONObject(it)
                } catch (e: Exception) {
                    null
                }
            }

            if (outcome == null || !outcome.optBoolean("ok")) {
                val error = outcome?.optString("error").orEmpty()
                Timber.w("Storage script failed: $error")
                activity?.toast(error.ifEmpty { getString(R.string.storage_write_failed) })
            }

            onWritten()
        }
    }

    /**
     * Build the script setting one item in the provided storage.
     * [JSONObject.quote] gives us a proper JavaScript string literal.
     */
    private fun setItemScript(target: Target, key: String, value: String): String =
        "try{window.${target.accessor}.setItem(${JSONObject.quote(key)},${JSONObject.quote(value)});''}" +
                "catch(e){String(e)}"

    /**
     * Build the script removing one item from the provided storage.
     */
    private fun removeItemScript(target: Target, key: String): String =
        "try{window.${target.accessor}.removeItem(${JSONObject.quote(key)});''}" +
                "catch(e){String(e)}"

    /**
     * One row of one of our dialogs: what it shows and what tapping it does.
     */
    private class Row(val title: String, val summary: String? = null, val onClick: (() -> Unit)? = null)

    /**
     * What one of our levels came back with: the rows to show, the records as a table, or why it has
     * none.
     */
    private sealed class Outcome {
        /** Our rows, under a title of their own when the payload knew better than we did. */
        class Rows(val rows: List<Row>, val title: String? = null) : Outcome()

        /**
         * The records of one object store, as a table: [columns] to head it with, then one list of
         * cells per record, and last the [action] row closing it.
         *
         * A record's cells hold one entry per column, in the order of [columns]; a record missing a
         * field the page has simply leaves an empty cell. Empty [columns] means no record on the page
         * had fields to show, and the table shows no header at all rather than a blank one.
         *
         * [total] is how many records the store holds, which the paging buttons are enabled from,
         * and [title] is what the dialog says it is showing. Tapping one cell, at the row and column
         * [onClick] is given, opens what that one cell holds; [onLongClick] gets the record a whole
         * row stands for, our table being one cell too narrow to say what a long press means.
         */
        class Table(
            val columns: List<String>,
            val cells: List<List<String>>,
            val action: Row?,
            val onClick: (row: Int, column: Int) -> Unit,
            val onLongClick: ((row: Int) -> Unit)? = null,
            val title: String,
            val total: Int
        ) : Outcome()

        /** Why there is nothing to show, as the page put it or as we did. */
        class Notice(val text: String) : Outcome()
    }

    /**
     * The list behind one of our dialogs: waiting for the page, our rows, our records as a table, or
     * why there are none.
     *
     * The waiting row comes with a spinner, see `list_item_loading`. Rows we have no click for, and
     * rows we do not have yet, are not selectable: tapping the waiting row must not open anything.
     */
    private class RowAdapter(private val context: Context) : BaseAdapter() {

        private var rows: List<Row> = emptyList()
        private var notice: String? = null
        private var loading = false
        private var table: Outcome.Table? = null

        /**
         * The list our rows are shown in, so that the row being scrolled can reach the others.
         */
        private var list: ViewGroup? = null

        /**
         * How far our table is scrolled sideways, in pixels.
         *
         * A table is one thing: its head, its records and its action row move together, or a column
         * would sit under one field name in the head and another name in the rows below it. Android
         * scrolls each of our rows on its own, so the offset is kept here and handed to every row as
         * it is bound, see [bindTable] and [syncScroll].
         */
        private var scrollX = 0

        /**
         * The least room one column gets, in pixels: [MIN_CELL_DP] at the density of this screen.
         */
        private val minCellWidth: Int =
            (context.resources.displayMetrics.density * MIN_CELL_DP).toInt()

        /**
         * What each column of the table on show is drawn at, in pixels, and what it was worked out
         * for: the page it belongs to and the width of the list showing it, see [columnWidths].
         */
        private var widths: IntArray = IntArray(0)
        private var widthsFor: Outcome.Table? = null
        private var widthsAt = -1

        /**
         * The width to draw each of the columns of [table] at, in pixels, [available] being how wide
         * the list showing it is.
         *
         * The columns share the width of the list while they can, and past [MIN_CELL_DP] each they
         * stop sharing it: the table then grows wider than the list, to be read by scrolling it
         * sideways. One is never narrower than the name heading it either, a field name having to be
         * read in full to say what the column under it holds. Widening one column widens the table
         * rather than taking room from its neighbours.
         *
         * Worked out once per page rather than once per row: every row of the table has to be given
         * the same answer, or a column would sit under one field name in the head and another name
         * in the rows below it.
         */
        private fun columnWidths(table: Outcome.Table, available: Int): IntArray {
            if (widthsFor === table && widthsAt == available) {
                return widths
            }
            val share = available / maxOf(1, table.columns.size)
            val measured = IntArray(table.columns.size) { column ->
                maxOf(minCellWidth, share, nameWidth(table.columns[column]))
            }
            widths = measured
            widthsFor = table
            widthsAt = available
            return measured
        }

        /**
         * How wide a column must be for the field name it is headed by to be read in full, in
         * pixels.
         *
         * Laid out for real, in the very layout the name will be drawn in: that is where its size,
         * its face and its padding come from, and guessing at them is how a name ends up cut off.
         */
        private fun nameWidth(name: String): Int {
            val probe = LayoutInflater.from(context)
                .inflate(R.layout.list_item_storage_header, null, false) as TextView
            probe.text = name
            probe.maxLines = 1
            probe.ellipsize = null
            probe.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            // And a pixel to spare: the width it is given back is a whole number of pixels, and one
            // rounded down from under the name would cut it off as surely as a narrow column would
            return probe.measuredWidth + 1
        }

        /**
         * Where our table starts, its header being the row above its records when it has columns.
         */
        private val headerRows: Int get() = if (table?.columns?.isNotEmpty() == true) 1 else 0

        private val actionRows: Int get() = if (table?.action != null) 1 else 0

        fun setLoading() {
            rows = emptyList()
            notice = null
            table = null
            loading = true
            notifyDataSetChanged()
        }

        fun setRows(rows: List<Row>) {
            this.rows = rows
            notice = null
            table = null
            loading = false
            notifyDataSetChanged()
        }

        fun setNotice(text: String) {
            rows = emptyList()
            notice = text
            table = null
            loading = false
            notifyDataSetChanged()
        }

        fun setTable(table: Outcome.Table) {
            this.table = table
            rows = emptyList()
            notice = null
            loading = false
            notifyDataSetChanged()
        }

        /**
         * Tap one of our rows, by position.
         *
         * Only our plain rows come through here. A table row is made of cells that are touch targets
         * of their own, and the one tapped knows which record and which column it stands for, which
         * this does not: the list's own item click is only ever given a row. What is left for a table
         * is the header row, whose cells are not touch targets, and there is nothing behind a column
         * name to open.
         */
        fun click(position: Int) {
            if (table == null) {
                rows.getOrNull(position)?.onClick?.invoke()
            }
        }

        // One line while we wait, or to say why there is nothing, one per row otherwise
        override fun getCount(): Int {
            val shown = table
            return when {
                loading || notice != null -> 1
                shown != null -> headerRows + shown.cells.size + actionRows
                else -> rows.size
            }
        }

        override fun getItem(position: Int): Any? = rows.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun isEnabled(position: Int): Boolean {
            val shown = table ?: return rows.getOrNull(position)?.onClick != null
            val record = position - headerRows
            return when {
                // The header names our columns, there is nothing behind it
                record < 0 -> false
                record < shown.cells.size -> true
                else -> shown.action?.onClick != null
            }
        }

        // Our plain rows, the waiting row, and our table rows
        override fun getViewTypeCount(): Int = 3

        override fun getItemViewType(position: Int): Int = when {
            loading || notice != null -> 1
            table != null -> 2
            else -> 0
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val shown = table
            val waiting = loading || notice != null
            // The list our rows end up in, which is how far a row scrolls sideways can reach them
            list = parent
            val view = convertView ?: LayoutInflater.from(context).inflate(
                when {
                    waiting -> R.layout.list_item_loading
                    shown != null -> R.layout.list_item_storage_table
                    else -> android.R.layout.simple_list_item_2
                },
                parent,
                false
            )

            when {
                waiting -> {
                    view.findViewById<ProgressBar>(R.id.loadingProgress).visibility =
                        if (loading) View.VISIBLE else View.GONE
                    view.findViewById<TextView>(R.id.loadingText).text =
                        notice ?: context.getString(R.string.loading)
                }

                shown != null -> bindTable(view, shown, position, parent.width)

                else -> {
                    val row = rows[position]
                    view.findViewById<TextView>(android.R.id.text1).text = forView(row.title)
                    view.findViewById<TextView>(android.R.id.text2).apply {
                        text = forView(row.summary)
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }
                }
            }

            return view
        }

        /**
         * Fill one table row: the header of our columns, one record, or the action row closing the
         * table.
         *
         * The cells are laid out here rather than in a layout of their own: how many there are and
         * what they hold comes from the page, the next one over only knows its own columns.
         *
         * Each of them is given a width of its own rather than a share of the row, see
         * [columnWidths]: that is what lets the table be wider than the list and be scrolled
         * sideways. [width] is how wide the list is, which is what those widths are worked out from.
         */
        private fun bindTable(view: View, table: Outcome.Table, position: Int, width: Int) {
            val cells = view.findViewById<LinearLayout>(R.id.storageCells)
            val scroll = view.findViewById<HorizontalScrollView>(R.id.storageCellsScroll)
            val divider = view.findViewById<View>(R.id.storageCellsDivider)
            val record = position - headerRows
            // A row measured before its list is laid out has no width to go by: the screen is the
            // next best thing, and only ever the width of a list that is about to have one
            val available = if (width > 0) width else context.resources.displayMetrics.widthPixels

            // Whatever the row we are recycling held, the columns of this page need not be its own
            cells.removeAllViews()
            divider.visibility = View.GONE

            val texts = when {
                record < 0 -> table.columns
                record < table.cells.size -> table.cells[record]
                else -> listOf(table.action?.title.orEmpty())
            }

            val layoutId = when {
                record < 0 -> R.layout.list_item_storage_header
                record < table.cells.size -> R.layout.list_item_storage_cell
                else -> R.layout.list_item_storage_action
            }

            // The room each column is given, which every row of the table is given back alike: that is
            // what lines a column up with the name heading it, however far the table is scrolled
            val columnPixels = columnWidths(table, available)

            texts.forEachIndexed { column, text ->
                val cell = LayoutInflater.from(context).inflate(layoutId, cells, false) as TextView
                cell.text = forView(text)
                // One column's worth per cell of the head and of the records. The action row is given
                // all of them: it stands for the table it closes rather than for a column of it, and
                // reaches as far as the columns above it do.
                val oneColumn = record < table.cells.size && column < columnPixels.size
                cell.layoutParams = LinearLayout.LayoutParams(
                    if (oneColumn) columnPixels[column] else columnPixels.sum(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // A cell is a touch target of its own, and the one that knows which record and which
                // column it stands for: the list's own click only ever tells us the row. It also
                // takes the tap before the list sees it, so a table row is never opened by accident
                // on the way to one of its cells.
                when {
                    // The header names our columns, there is nothing behind it to open
                    record < 0 -> Unit

                    record < table.cells.size -> {
                        cell.setOnClickListener { table.onClick(record, column) }
                        // A long press on any cell of a record is a long press on the record itself
                        table.onLongClick?.let { open ->
                            cell.setOnLongClickListener {
                                open(record)
                                true
                            }
                        }
                    }

                    else -> cell.setOnClickListener { table.action?.onClick?.invoke() }
                }
                cells.addView(cell)
            }

            // This row takes the offset the table is at, and takes the table wherever it is scrolled
            // to. The offset is read again once the row is laid out: scrolled before that, our cells
            // have no width yet and the row would clamp the offset back to nothing.
            scroll.post { scroll.scrollTo(scrollX, 0) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                scroll.setOnScrollChangeListener { _, scrolledTo, _, _, _ ->
                    if (scrolledTo != scrollX) {
                        scrollX = scrolledTo
                        syncScroll(scroll)
                    }
                }
            }

            // What follows the header is one record after another, the line says as much
            divider.visibility = if (record < 0) View.VISIBLE else View.GONE
        }

        /**
         * Move every other row of our table to the offset one of them was scrolled to.
         *
         * Android has no table to scroll as one, only the rows we made, so the row being dragged
         * says where it went and each of the others follows. The rows off screen need nothing: they
         * are given the offset again as they are bound, see [bindTable].
         */
        private fun syncScroll(from: HorizontalScrollView) {
            val group = list ?: return
            for (index in 0 until group.childCount) {
                val other = group.getChildAt(index)
                    .findViewById<HorizontalScrollView>(R.id.storageCellsScroll)
                if (other != null && other !== from) {
                    other.scrollTo(scrollX, 0)
                }
            }
        }

        /**
         * What we put in the view, as opposed to what the row holds.
         *
         * A stored value can be megabytes long, and laying one line of that out is quadratic in its
         * length: the main thread sits in StaticLayout long enough for the system to declare us
         * unresponsive. One screenful of characters is all a single line can show anyway. The row
         * keeps the whole value, so the editor we open from it still reads and writes all of it.
         */
        private fun forView(text: String?): String? = text?.take(MAX_VIEW_CHARS)
    }

    /**
     * Reads one of our page globals until the script we ran alongside it reports there, then hands
     * the payload over.
     *
     * There is no callback for an asynchronous JavaScript value: we run a synchronous script handing
     * us whatever the global holds, and come back later while it still holds nothing. Every poll
     * stands alone, so that the panel loading and a dialog drilled into it can wait on two globals
     * at once.
     */
    private inner class Poll(
        private val webView: WebView,
        private val variable: String,
        private val attempts: Int,
        private val onPayload: (String?) -> Unit
    ) : Runnable {

        private var attempt = 0
        private var stopped = false

        fun start() {
            polls.add(this)
            pollHandler.post(this)
        }

        /**
         * Stop waiting without reporting anything: whatever the page has to say is not ours to show
         * any more.
         */
        fun stop() {
            stopped = true
            polls.remove(this)
            pollHandler.removeCallbacks(this)
        }

        override fun run() {
            // Our page may be gone by now, or the user may have switched tab on us
            if (stopped || !isAdded || webView !== currentWebView()) {
                stop()
                return
            }

            webView.evaluateJavascript(readScript(variable)) { result ->
                if (stopped || !isAdded || webView !== currentWebView()) {
                    stop()
                    return@evaluateJavascript
                }

                val payload = decodeString(result)

                // Nothing there yet: come back later, unless we have waited long enough
                if (payload == null && attempt < attempts) {
                    attempt++
                    pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                    return@evaluateJavascript
                }

                stop()
                if (payload != null) {
                    webView.evaluateJavascript(clearScript(variable), null)
                }
                onPayload(payload)
            }
        }
    }

    /**
     * One drill-down dialog, which opens with a spinner and swaps it for its rows once the page
     * answered.
     *
     * The page cannot be asked synchronously what a database or a cache holds, so the dialog comes up
     * right away and says it is working on it, rather than leaving the user staring at nothing while
     * a large store is being read.
     *
     * With a [pageSize] the dialog reads [script] one page at a time and gives itself the buttons to
     * step through them, see [read]. Levels that hold in one page leave it at zero and are read
     * whole, as they always were.
     */
    private inner class Level(
        private val title: String,
        private val script: (offset: Int) -> String,
        private val variable: String,
        private val onClosed: () -> Unit = {},
        private val pageSize: Int = 0,
        private val done: (payload: String?, reload: () -> Unit) -> Outcome
    ) {

        private val adapter = RowAdapter(requireContext())
        private lateinit var dialog: AlertDialog

        /**
         * True once we are gone: what our script reports now is not ours to show any more.
         */
        private var closed = false

        /**
         * The read we have in flight, so that closing the dialog does not leave it running.
         */
        private var poll: Poll? = null

        /**
         * Where the records we are showing start in their store, and how many the store holds.
         *
         * [total] is what the last page we read reported, and stays put when a page fails to read:
         * the page on screen is still the one it described, and its buttons still go by it.
         */
        private var offset = 0
        private var total = 0

        fun show() {
            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                // No item click listener of its own: the one this builder installs also dismisses
                // the dialog on every tap, and a tap here opens the next level *on top of* this one,
                // which then has to stay up to be read again when that level closes.
                .setAdapter(adapter, null)
                .setNegativeButton(R.string.action_cancel, null)

            if (pageSize > 0) {
                // Cancel keeps the far end of the dialog to itself, stepping is done from the two
                // buttons next to it
                builder.setNeutralButton(R.string.action_previous_page, null)
                builder.setPositiveButton(R.string.action_next_page, null)
            }

            dialog = builder.create()
            // The builder's create leaves the dialog's views unmade: its own create is what makes
            // them, the buttons we are about to wire up among them. See [launch], which does the
            // same before it reaches the content panel it patches.
            dialog.create()
            // Ours, which only ever opens a level: what the builder would have wired up instead
            // dismisses us on the same tap. See AlertController's own item click listener.
            dialog.listView?.setOnItemClickListener { _, _, position, _ -> adapter.click(position) }

            if (pageSize > 0) {
                // Ours too, and set once the dialog is built, for the same reason: the listeners the
                // builder installs dismiss it, and stepping through a store does not leave it.
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { step(-pageSize) }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { step(pageSize) }
            }

            dialog.setOnDismissListener {
                closed = true
                poll?.stop()
                levels.remove(this)
                onClosed()
            }

            levels.add(this)
            dialog.show()
            read()
        }

        /**
         * Ask the page again and show what it answers, keeping the whole dialog in place.
         */
        fun read() {
            if (closed || !isAdded) {
                return
            }

            adapter.setLoading()
            // Whatever count our title mentioned is about to be replaced by a fresh one
            dialog.setTitle(title)
            // Nothing to step through until the page we are asking for answers
            setPagingEnabled(previous = false, next = false)

            poll?.stop()
            poll = runAsync(script(offset), variable, LEVEL_TIMEOUT_MS) { payload ->
                if (!closed) {
                    show(done(payload, ::read))
                }
            }
        }

        /**
         * Move our page by [delta] records and read it.
         */
        private fun step(delta: Int) {
            offset = (offset + delta).coerceAtLeast(0)
            read()
        }

        fun dismiss() {
            dialog.dismiss()
        }

        private fun show(outcome: Outcome) {
            when (outcome) {
                is Outcome.Rows -> {
                    outcome.title?.let { dialog.setTitle(it) }
                    adapter.setRows(outcome.rows)
                }

                is Outcome.Table -> {
                    dialog.setTitle(outcome.title)
                    total = outcome.total
                    adapter.setTable(outcome)
                }

                is Outcome.Notice -> adapter.setNotice(outcome.text)
            }

            // What is left to step through, if anything: a page that failed to read leaves the one
            // we have on screen, and its buttons keep saying whether we can back out of it
            setPagingEnabled(previous = offset > 0, next = offset + pageSize < total)
        }

        /**
         * Let the user step through what we are reading, or not.
         */
        private fun setPagingEnabled(previous: Boolean, next: Boolean) {
            if (pageSize <= 0 || closed || !isAdded) {
                return
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = previous
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = next
        }
    }

    companion object {
        /**
         * How many records of one object store we read at a time.
         *
         * The records level reads one page on opening and one more per tap on 下一页, rather than a
         * fixed number of them up front: what is read is what is on screen, so a store holding
         * thousands of records opens as fast as a store holding a handful.
         */
        private const val RECORDS_PAGE_SIZE = 20

        /**
         * How many URLs per cache we read at most.
         * The count we display is the real one.
         */
        private const val MAX_CACHE_REQUESTS = 100

        /**
         * How much of one record field one table cell carries.
         *
         * A cell shows a couple of lines at most, so this only has to be longer than what fits on a
         * wide screen. It bounds what a page hands us per record, see [recordsScript].
         */
        private const val MAX_CELL_CHARS = 200

        /**
         * The least room one column of the records table gets, in dp.
         *
         * The columns share the width of the list while they can, so a store holding two fields reads
         * on one screen with both of them whole. Past a handful of them sharing would leave each too
         * little to read a field name in -- and that name is the only thing saying what a column
         * holds. So each of them keeps this much, the table growing wider than the screen and being
         * read by scrolling it sideways, see [RowAdapter].
         */
        private const val MIN_CELL_DP = 120f

        /**
         * The longest value we are willing to put in an editor.
         *
         * One value, not one record: a field is edited on its own, and a record holding one huge
         * field is still editable field by field. Anything longer is read back to the user as a
         * notice instead, see [showCellDialog].
         *
         * Laying out one line of text costs the main thread time quadratic in its length, which is
         * why this was a tenth of this size when the editor took whole records at once. A megabyte
         * value is the user's deliberate choice: it is what makes a big one editable, and the layout
         * time that comes with it is a cost paid on opening that one value.
         */
        private const val VALUE_EDIT_MAX_CHARS = 1024 * 1024

        /**
         * How much of one value or key we hand to a view.
         *
         * Not a limit on what we read or write, only on what a single line of a dialog shows, see
         * [RowAdapter.forView]. Anything longer than a wide screenful of characters is off screen.
         */
        private const val MAX_VIEW_CHARS = 512

        private const val POLL_INTERVAL_MS = 100L

        /**
         * How long we give the page to answer each of our scripts.
         *
         * The panel only reads names, it has no business taking seconds. Drilling into a database
         * does open it, and counting the records of a big one can take a moment.
         */
        private const val PANEL_TIMEOUT_MS = 5_000L
        private const val LEVEL_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 5_000L

        /**
         * Where each of our scripts stashes its JSON payload.
         */
        private const val PANEL_VARIABLE = "_fulgurisStoragePanel"
        private const val STORES_VARIABLE = "_fulgurisStorageStores"
        private const val RECORDS_VARIABLE = "_fulgurisStorageRecords"
        private const val REQUESTS_VARIABLE = "_fulgurisStorageRequests"

        /**
         * Where [valueScript] stashes the one value it read.
         *
         * A global of its own: the records table polls the store if its page is still being read,
         * and sharing [RECORDS_VARIABLE] would let a value read be taken for that page.
         */
        private const val VALUE_VARIABLE = "_fulgurisStorageValue"

        /**
         * Where our write scripts stash their outcome, a JSON object with an `ok` flag and an
         * `error` message.
         */
        private const val RESULT_VARIABLE = "_fulgurisStorageResult"

        /**
         * Drop one of our page globals, so that a stale value is never taken for a fresh one.
         */
        private fun clearScript(variable: String) = "window.$variable = null"

        /**
         * Read one of our page globals. Nothing there reads back as JSON null, which our decoder
         * turns into null, telling us the script has not reported yet.
         */
        private fun readScript(variable: String) = "window.$variable"

        /**
         * Page side helpers every one of our scripts is given: serializing the values JSON cannot
         * carry, and opening a database the way our error reporting wants it.
         */
        private val JS_HELPERS = """
            // Values JSON cannot carry are tagged rather than silently dropped. A string is what our
            // callers are given, always: `stringify` hands back nothing at all for a value that has
            // no text of its own, `undefined` among them, and a store is free to hold one. Our
            // callers cut what they are handed - see `cell` in recordsScript - so nothing else can
            // reach them, an exception thrown there aborting the whole transaction it ran in.
            //
            // A tag is a stand-in, not the value it stands for: writing that text back stores the
            // tag in its place. `lossy` carries word of that to whoever asked for the text, so that
            // an editor opening it knows not to offer to save it again.
            var lossy = false;
            var json = function(value, space){
              var seen = [];
              try {
                var text = JSON.stringify(value, function(key, item){
                  if (typeof item === 'bigint') { lossy = true; return {__fulgurisType: 'BigInt', value: String(item)}; }
                  // What a replacer is given has already been through the value's own `toJSON`, and
                  // a Date has one: the text here is what it would be written as, not the Date. The
                  // holder is where the value as it was stored is still to be found, and a date we
                  // do not recognize as one would be handed to an editor as text it could save.
                  if (this[key] instanceof Date) { lossy = true; return {__fulgurisType: 'Date', value: item}; }
                  if (typeof Blob !== 'undefined' && item instanceof Blob) { lossy = true; return {__fulgurisType: 'Blob', size: item.size, mime: item.type}; }
                  if (typeof ArrayBuffer !== 'undefined' && item instanceof ArrayBuffer) { lossy = true; return {__fulgurisType: 'ArrayBuffer', size: item.byteLength}; }
                  if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(item)) { lossy = true; return {__fulgurisType: item.constructor.name, size: item.byteLength}; }
                  if (item && typeof item === 'object') {
                    if (seen.indexOf(item) >= 0) { lossy = true; return '[Circular]'; }
                    seen.push(item);
                  }
                  return item;
                }, space);
                return text === undefined ? String(value) : text;
              } catch (error) {
                // Whatever we could not serialize is not what its text says either
                lossy = true;
                return String(value);
              }
            };

            // Indented, the way we show a whole value
            var image = function(value){ return json(value, 2); };

            // True for the values we can take apart field by field: arrays, dates and the like have
            // no fields of their own to head a column with
            var hasFields = function(value){
              return Object.prototype.toString.call(value) === '[object Object]';
            };

            // Another tab can hold the database open, which we report rather than wait on
            var openDatabase = function(name){
              return new Promise(function(resolve){
                var request;
                try {
                  request = indexedDB.open(name);
                } catch (error) {
                  resolve({database: null, error: String(error)});
                  return;
                }
                request.onerror = function(){ resolve({database: null, error: String(request.error || '')}); };
                request.onblocked = function(){ resolve({database: null, error: ''}); };
                request.onsuccess = function(){ resolve({database: request.result, error: ''}); };
              });
            };
        """.trimIndent()

        /**
         * Wrap one of our page side scripts: the IIFE they all want, and the helpers they share.
         */
        private fun pageScript(body: String): String = "(function(){\n$JS_HELPERS\n$body\n})()"

        /**
         * Read both web storages, the IndexedDB database names and the CacheStorage cache names of
         * the current origin, and stash the result as a JSON string on [PANEL_VARIABLE].
         *
         * Names only: opening a database or a cache to count what is inside is what made this slow on
         * pages holding a lot of data. That now happens when the user opens one of them, see
         * [storesScript] and [requestsScript].
         */
        private val PANEL_SCRIPT = pageScript("""
            window.$PANEL_VARIABLE = null;

            var webStorage = function(storage){
              var items = {};
              try {
                for (var index = 0; index < storage.length; index++) {
                  var key = storage.key(index);
                  items[key] = storage.getItem(key);
                }
              } catch (error) {}
              return items;
            };

            var readDatabases = function(){
              return new Promise(function(resolve){
                if (!window.indexedDB || typeof indexedDB.databases !== 'function') {
                  resolve({supported: false, databases: []});
                  return;
                }
                indexedDB.databases().then(function(list){
                  var databases = [];
                  for (var index = 0; index < list.length; index++) {
                    databases.push(list[index].name);
                  }
                  resolve({supported: true, databases: databases.sort()});
                }).catch(function(error){
                  resolve({supported: true, databases: []});
                });
              });
            };

            var readCaches = function(){
              return new Promise(function(resolve){
                if (!window.caches || typeof caches.keys !== 'function') {
                  resolve({supported: false, names: []});
                  return;
                }
                caches.keys().then(function(names){
                  resolve({supported: true, names: names.sort()});
                }).catch(function(error){
                  resolve({supported: true, names: []});
                });
              });
            };

            var result = {origin: '', local: {}, session: {}, idb: null, caches: null};

            try { result.origin = window.location.origin || ''; } catch (error) {}
            try { result.local = webStorage(window.localStorage); } catch (error) {}
            try { result.session = webStorage(window.sessionStorage); } catch (error) {}

            // Whatever happens our global gets written, the panel is not left waiting on us
            readDatabases().then(function(idb){
              result.idb = idb;
              return readCaches();
            }).then(function(caches){
              result.caches = caches;
              window.$PANEL_VARIABLE = JSON.stringify(result);
            }).catch(function(error){
              window.$PANEL_VARIABLE = JSON.stringify(result);
            });
        """.trimIndent())

        /**
         * Read the object stores of one database: their names, their key paths and how many records
         * each holds.
         */
        private fun storesScript(database: String): String = pageScript("""
            window.$STORES_VARIABLE = null;

            var report = function(payload){
              window.$STORES_VARIABLE = JSON.stringify(payload);
            };

            var storeMeta = function(db, name){
              return new Promise(function(resolve){
                var store = {name: name, keyPath: null, autoIncrement: false, count: 0};
                try {
                  var transaction = db.transaction(name, 'readonly');
                  var objectStore = transaction.objectStore(name);
                  var keyPath = objectStore.keyPath;
                  // A compound key path is an array, we only need to know the key is in the value
                  if (keyPath && typeof keyPath !== 'string') { keyPath = keyPath.join(','); }
                  store.keyPath = keyPath === undefined ? null : keyPath;
                  store.autoIncrement = !!objectStore.autoIncrement;
                  var count = objectStore.count();
                  count.onsuccess = function(){ store.count = count.result; };
                  transaction.oncomplete = function(){ resolve(store); };
                  transaction.onerror = function(){ resolve(store); };
                  transaction.onabort = function(){ resolve(store); };
                } catch (error) {
                  resolve(store);
                }
              });
            };

            openDatabase(${JSONObject.quote(database)}).then(function(opened){
              if (!opened.database) {
                report({ok: false, error: opened.error});
                return;
              }

              var db = opened.database;
              var names = [];
              for (var index = 0; index < db.objectStoreNames.length; index++) {
                names.push(db.objectStoreNames[index]);
              }

              var stores = [];
              var next = function(position){
                if (position >= names.length) {
                  db.close();
                  report({ok: true, stores: stores});
                  return;
                }
                storeMeta(db, names[position]).then(function(store){
                  stores.push(store);
                  next(position + 1);
                });
              };
              next(0);
            });
        """.trimIndent())

        /**
         * Read one page of the records of one object store: [limit] of them at most, starting at
         * [offset], and report how many the store really holds.
         *
         * The records in front of the page are skipped with a cursor's `advance`, which moves over
         * them without reading any of them: only the page we are about to show is deserialized, which
         * is what keeps a store holding thousands of records from being read for a single screenful.
         *
         * Each record comes back taken apart: its key, and its top level fields for our table to show
         * in columns. A record whose value is not a plain object has no fields to head a column with
         * and comes back as text instead, in the table's single value column.
         *
         * Only what a cell can show of a value is sent, see [MAX_CELL_CHARS]: the editor reads the one
         * value it opens from the page again, see [valueScript], and a page-sized payload holding no
         * megabyte values is what keeps paging light.
         */
        private fun recordsScript(
            database: String,
            store: String,
            offset: Int,
            limit: Int
        ): String = pageScript("""
            window.$RECORDS_VARIABLE = null;

            var OFFSET = $offset;
            var LIMIT = $limit;
            var CELL_MAX = $MAX_CELL_CHARS;

            var report = function(payload){
              window.$RECORDS_VARIABLE = JSON.stringify(payload);
            };

            var clip = function(text, max){
              return text.length > max ? text.substring(0, max) : text;
            };

            // What one record has to say for one of its fields
            var cell = function(value){
              return clip(json(value, 0), CELL_MAX);
            };

            // The fields our columns are headed with, in the order the records are met in. `record`
            // below runs in this scope, so this is the only place it can live: declared inside the
            // callback that walks the store it would be a variable of its own, unknown to `record`,
            // and the lookup throwing inside an event handler aborts the whole read it is running in.
            var fields = [];

            var record = function(row){
              var item = {key: image(row.key), cells: null};
              // Only a plain object has fields of its own to show in columns. Anything else - a
              // number, an array, a date - the table shows whole, in a column of its own, as far as
              // that one cell reaches: opening it reads the value again.
              if (hasFields(row.value)) {
                var cells = {};
                var names = Object.keys(row.value);
                for (var index = 0; index < names.length; index++) {
                  cells[names[index]] = cell(row.value[names[index]]);
                  if (fields.indexOf(names[index]) < 0) { fields.push(names[index]); }
                }
                item.cells = cells;
              } else {
                item.value = cell(row.value);
              }
              return item;
            };

            openDatabase(${JSONObject.quote(database)}).then(function(opened){
              if (!opened.database) {
                report({ok: false, error: opened.error});
                return;
              }

              var db = opened.database;
              try {
                var transaction = db.transaction(${JSONObject.quote(store)}, 'readonly');
                var objectStore = transaction.objectStore(${JSONObject.quote(store)});
                var records = [];
                var total = 0;
                var count = objectStore.count();
                count.onsuccess = function(){ total = count.result; };
                var cursor = objectStore.openCursor();
                // Position in the store, our page being the first row we bother reading values from
                var first = true;
                cursor.onsuccess = function(event){
                  var row = event.target.result;
                  if (!row || records.length >= LIMIT) { return; }
                  if (first) {
                    first = false;
                    // `advance` throws on 0, and there is nothing to skip anyway
                    if (OFFSET > 0) { row.advance(OFFSET); return; }
                  }
                  records.push(record(row));
                  row.continue();
                };
                transaction.oncomplete = function(){
                  db.close();
                  report({ok: true, count: total, offset: OFFSET, fields: fields, records: records});
                };
                transaction.onerror = function(){ db.close(); report({ok: false, error: String(transaction.error || '')}); };
                transaction.onabort = function(){ db.close(); report({ok: false, error: String(transaction.error || '')}); };
              } catch (error) {
                db.close();
                report({ok: false, error: String(error)});
              }
            });
        """.trimIndent())

        /**
         * Read the requests one cache holds, up to [MAX_CACHE_REQUESTS] of them, and report how many
         * it really holds.
         */
        private fun requestsScript(cache: String): String = pageScript("""
            window.$REQUESTS_VARIABLE = null;

            var MAX_URLS = $MAX_CACHE_REQUESTS;

            var report = function(payload){
              window.$REQUESTS_VARIABLE = JSON.stringify(payload);
            };

            if (!window.caches || typeof caches.open !== 'function') {
              report({ok: false, error: ''});
              return;
            }

            caches.open(${JSONObject.quote(cache)}).then(function(opened){
              return opened.keys().then(function(requests){
                var urls = [];
                for (var index = 0; index < requests.length && index < MAX_URLS; index++) {
                  urls.push(requests[index].url);
                }
                report({ok: true, count: requests.length, urls: urls});
              });
            }).catch(function(error){
              report({ok: false, error: String(error)});
            });
        """.trimIndent())

        /**
         * Build the script writing, and optionally removing first, one record of the provided object
         * store.
         *
         * Everything runs in one transaction, so that a failed write does not eat the record it was
         * meant to replace. Keys and values are handed to the page as the JSON text our dump
         * produced.
         */
        private fun recordScript(
            database: String,
            store: String,
            deleteKey: String?,
            key: String?,
            value: String?
        ): String {
            val remove = if (deleteKey == null) {
                ""
            } else {
                "objectStore.delete(JSON.parse(${JSONObject.quote(deleteKey)}));"
            }

            val write = when {
                value == null -> ""
                key == null -> "objectStore.put(JSON.parse(${JSONObject.quote(value)}));"
                else -> "objectStore.put(JSON.parse(${JSONObject.quote(value)})," +
                        "JSON.parse(${JSONObject.quote(key)}));"
            }

            return """
                (function(){
                  var report = function(ok, error){
                    window.$RESULT_VARIABLE = JSON.stringify({ok: ok, error: error || ''});
                  };
                  try {
                    var request = indexedDB.open(${JSONObject.quote(database)});
                    request.onerror = function(){ report(false, String(request.error || '')); };
                    request.onblocked = function(){ report(false, ''); };
                    request.onsuccess = function(){
                      var db = request.result;
                      try {
                        var transaction = db.transaction(${JSONObject.quote(store)}, 'readwrite');
                        var objectStore = transaction.objectStore(${JSONObject.quote(store)});
                        $remove
                        $write
                        transaction.oncomplete = function(){ db.close(); report(true, ''); };
                        transaction.onerror = function(){ db.close(); report(false, String(transaction.error || '')); };
                        transaction.onabort = function(){ db.close(); report(false, String(transaction.error || '')); };
                      } catch (error) {
                        db.close();
                        report(false, String(error));
                      }
                    };
                  } catch (error) {
                    report(false, String(error));
                  }
                })()
            """.trimIndent()
        }

        /**
         * Read the one value an editor is about to open, and report it as JSON text.
         *
         * [field] picks one field of the record's value, null for the whole value: our table shows
         * one value per cell, and reading a whole record to edit one of its fields would be reading
         * every other megabyte of it for nothing.
         *
         * What comes back is that value's text, its true length, and whether writing that text back
         * would keep it: a value holding a date, a blob or a cycle comes out as a tagged placeholder,
         * which is a stand-in rather than the value, see [JS_HELPERS] and [VALUE_EDIT_MAX_CHARS].
         *
         * A field the record carries no value for is reported as an empty text, not as the text of
         * `undefined`: that is the one an editor opens empty.
         */
        private fun valueScript(
            database: String,
            store: String,
            key: String,
            field: String?
        ): String = pageScript("""
            window.$VALUE_VARIABLE = null;

            var KEY = ${JSONObject.quote(key)};
            var FIELD = ${if (field == null) "null" else JSONObject.quote(field)};
            var VALUE_MAX = $VALUE_EDIT_MAX_CHARS;

            var report = function(payload){
              window.$VALUE_VARIABLE = JSON.stringify(payload);
            };

            var clip = function(text, max){
              return text.length > max ? text.substring(0, max) : text;
            };

            openDatabase(${JSONObject.quote(database)}).then(function(opened){
              if (!opened.database) {
                report({ok: false, error: opened.error});
                return;
              }

              var db = opened.database;
              var parsed;
              try {
                parsed = JSON.parse(KEY);
              } catch (error) {
                db.close();
                report({ok: false, error: String(error)});
                return;
              }

              try {
                var transaction = db.transaction(${JSONObject.quote(store)}, 'readonly');
                var objectStore = transaction.objectStore(${JSONObject.quote(store)});
                // What a handler does is sort out what was read, and nothing else: anything that
                // could throw runs before the transaction or waits for it to complete. An exception
                // here would abort the read and leave us with an abort message instead of a value.
                var payload = null;
                var read = objectStore.get(parsed);
                read.onsuccess = function(event){
                  // `get` hands back the value itself: the key is one we already know, we read the
                  // record under it
                  var record = event.target.result;
                  if (record === undefined) { payload = {ok: false, error: ''}; return; }
                  var value = FIELD === null ? record
                    : (record === null || typeof record !== 'object' ? undefined : record[FIELD]);
                  if (value === undefined) { payload = {ok: true, size: 0, text: '', lossy: false}; return; }
                  var text = json(value, 2);
                  payload = {ok: true, size: text.length, text: clip(text, VALUE_MAX), lossy: lossy};
                };
                transaction.oncomplete = function(){
                  db.close();
                  report(payload || {ok: false, error: ''});
                };
                transaction.onerror = function(){ db.close(); report({ok: false, error: String(transaction.error || '')}); };
                transaction.onabort = function(){ db.close(); report({ok: false, error: String(transaction.error || '')}); };
              } catch (error) {
                db.close();
                report({ok: false, error: String(error)});
              }
            });
        """.trimIndent())

        /**
         * Build the script writing -- or, given an empty [text], removing -- one field of one record.
         *
         * An empty text is how a column is cleared: the record then carries no such field at all,
         * which is also what a record not holding every field of its store looks like.
         *
         * A store with a key path derives each record's key from the record itself, so the record is
         * removed under its old key before being written back under the one its new value gives it.
         * Removal and write happen in the same transaction, and a write we cannot make rolls the
         * removal back: failing to set a field must not eat the record it was meant to change.
         */
        private fun fieldScript(
            database: String,
            store: String,
            keyPath: String?,
            key: String,
            field: String,
            text: String
        ): String {
            // The key we read the record under, to be dropped when the store derives its keys itself
            val remove = "objectStore.delete(parsed);"
            // A store without a key path keeps the key it was read under, one with a key path derives
            // it from the record we just handed back
            val write = if (keyPath == null) {
                "objectStore.put(record, parsed);"
            } else {
                "objectStore.put(record);"
            }

            val body = """
            var KEY = ${JSONObject.quote(key)};
            var FIELD = ${JSONObject.quote(field)};
            var TEXT = ${JSONObject.quote(text)};

            // Reported once at most: a failure found inside a handler must not be overwritten by the
            // transaction completing behind it
            var reported = false;
            var report = function(ok, error){
              if (reported) { return; }
              reported = true;
              window.$RESULT_VARIABLE = JSON.stringify({ok: ok, error: error || ''});
            };

            // Outside the transaction: a key or a value we cannot parse is reported before we open
            // anything. An empty text is not a value but the absence of one.
            var parsed;
            var newValue;
            try {
              parsed = JSON.parse(KEY);
              newValue = TEXT === '' ? undefined : JSON.parse(TEXT);
            } catch (error) {
              report(false, String(error));
              return;
            }

            try {
              var request = indexedDB.open(${JSONObject.quote(database)});
              request.onerror = function(){ report(false, String(request.error || '')); };
              request.onblocked = function(){ report(false, ''); };
              request.onsuccess = function(){
                var db = request.result;
                try {
                  var transaction = db.transaction(${JSONObject.quote(store)}, 'readwrite');
                  var objectStore = transaction.objectStore(${JSONObject.quote(store)});
                  var read = objectStore.get(parsed);
                  // The write is issued from inside this handler: a transaction is finished with as
                  // soon as the task that made the read returns, so anything put off to a later task
                  // is refused with a TransactionInactiveError.
                  read.onsuccess = function(event){
                    // `get` hands back the value itself, and the record we write back is that very
                    // value with one field changed
                    var record = event.target.result;
                    // A record deleted meanwhile is not one to write a field into
                    if (record === undefined) { report(false, ''); return; }
                    if (!hasFields(record)) { report(false, ''); return; }
                    if (newValue === undefined) { delete record[FIELD]; } else { record[FIELD] = newValue; }
                    try {
                      $remove
                      $write
                    } catch (error) {
                      // Nothing we issued commits: a write we cannot make must not leave the record
                      // without the key we removed it under
                      try { transaction.abort(); } catch (failed) {}
                      report(false, String(error));
                    }
                  };
                  transaction.oncomplete = function(){ db.close(); report(true, ''); };
                  transaction.onerror = function(){ db.close(); report(false, String(transaction.error || '')); };
                  transaction.onabort = function(){ db.close(); report(false, String(transaction.error || '')); };
                } catch (error) {
                  db.close();
                  report(false, String(error));
                }
              };
            } catch (error) {
              report(false, String(error));
            }
            """.trimIndent()

            return pageScript(body)
        }

        /**
         * Build the script renaming one record of a store keeping its keys on the side.
         *
         * The value is never read out of the page here: that is what lets a record too big to open
         * still be renamed. A key already in use is taken over rather than left as a second record,
         * as in [recordScript].
         */
        private fun keyScript(
            database: String,
            store: String,
            oldKey: String,
            newKey: String
        ): String {
            val body = """
            var OLD = ${JSONObject.quote(oldKey)};
            var NEW = ${JSONObject.quote(newKey)};

            var reported = false;
            var report = function(ok, error){
              if (reported) { return; }
              reported = true;
              window.$RESULT_VARIABLE = JSON.stringify({ok: ok, error: error || ''});
            };

            // The key is not going anywhere: nothing to write, and the record is there to prove it
            if (OLD === NEW) { report(true, ''); return; }

            var oldKey;
            var newKey;
            try {
              oldKey = JSON.parse(OLD);
              newKey = JSON.parse(NEW);
            } catch (error) {
              report(false, String(error));
              return;
            }

            try {
              var request = indexedDB.open(${JSONObject.quote(database)});
              request.onerror = function(){ report(false, String(request.error || '')); };
              request.onblocked = function(){ report(false, ''); };
              request.onsuccess = function(){
                var db = request.result;
                try {
                  var transaction = db.transaction(${JSONObject.quote(store)}, 'readwrite');
                  var objectStore = transaction.objectStore(${JSONObject.quote(store)});
                  var read = objectStore.get(oldKey);
                  // Reading first keeps a rename from inventing a record: there has to be something
                  // under the old key for there to be anything to move
                  read.onsuccess = function(event){
                    // `get` hands back the value itself, the one this renamed record is to keep
                    var record = event.target.result;
                    if (record === undefined) { report(false, ''); return; }
                    try {
                      objectStore.delete(oldKey);
                      objectStore.put(record, newKey);
                    } catch (error) {
                      try { transaction.abort(); } catch (failed) {}
                      report(false, String(error));
                    }
                  };
                  transaction.oncomplete = function(){ db.close(); report(true, ''); };
                  transaction.onerror = function(){ db.close(); report(false, String(transaction.error || '')); };
                  transaction.onabort = function(){ db.close(); report(false, String(transaction.error || '')); };
                } catch (error) {
                  db.close();
                  report(false, String(error));
                }
              };
            } catch (error) {
              report(false, String(error));
            }
            """.trimIndent()

            return pageScript(body)
        }
    }
}
