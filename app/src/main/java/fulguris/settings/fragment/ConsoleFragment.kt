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

import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import dagger.hilt.android.AndroidEntryPoint
import fulguris.R
import fulguris.activity.WebBrowserActivity
import fulguris.extensions.px
import fulguris.view.WebPageTab
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment to display all JavaScript console messages from the current page
 * Shows message level, source, line number, and timestamp
 *
 * Also provides a REPL to evaluate JavaScript expressions in the current page,
 * a level filter, an expand-on-tap for long messages and a clear action.
 */
@AndroidEntryPoint
class ConsoleFragment : AbstractSettingsFragment() {

    /**
     * A JavaScript expression evaluated from the input bar along with its result.
     */
    private data class ReplEntry(val code: String, val result: String)

    /// Levels offered by our filter, a null level meaning "show everything"
    private val filterLevels: List<ConsoleMessage.MessageLevel?> = listOf(
        null,
        ConsoleMessage.MessageLevel.ERROR,
        ConsoleMessage.MessageLevel.WARNING,
        ConsoleMessage.MessageLevel.LOG,
        ConsoleMessage.MessageLevel.TIP,
        ConsoleMessage.MessageLevel.DEBUG
    )

    private var messages: List<WebPageTab.ConsoleMessage> = emptyList()
    private val replEntries = mutableListOf<ReplEntry>()

    /// Preferences we add and remove ourselves, as opposed to the ones coming from our XML
    private val dynamicPreferences = mutableListOf<Preference>()

    private var filterLevel: ConsoleMessage.MessageLevel? = null
    private var input: EditText? = null
    private var lastEntry: Preference? = null
    private var toolBarBound = false

    override fun titleResourceId(): Int = R.string.pref_category_title_console

    override fun providePreferencesXmlResource() = R.xml.preference_console

    override fun onResume() {
        super.onResume()
        // Our input bar lives in our parent bottom sheet, which is only fully attached by then
        if (!toolBarBound) {
            toolBarBound = true
            bindToolBar(requireView())
        }
        // We show a snapshot of what the tab collected, taken in `refresh`. Without this we would
        // only ever take it again when the user asked us to: a line the page prints while we are on
        // screen, our own REPL output included, would wait for that - reopening the panel being the
        // easiest way to ask.
        currentTab()?.addConsoleMessageListener(onConsoleMessage)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        currentTab()?.removeConsoleMessageListener(onConsoleMessage)
    }

    /**
     * Take our snapshot again, at most once per frame: a page printing in a loop is one redraw per
     * frame here, not one per line.
     */
    private fun refreshSoon() {
        if (refreshPending || !isAdded) {
            return
        }

        val root = view ?: return
        refreshPending = true
        root.post {
            refreshPending = false
            if (isAdded) {
                refresh()
            }
        }
    }

    /**
     * The tab whose console we are showing, if we still have one.
     */
    private fun currentTab(): WebPageTab? =
        (activity as? WebBrowserActivity)?.tabsManager?.currentTab

    /// Handed to the tab, and held so that [onPause] can take back the very same thing
    private val onConsoleMessage: () -> Unit = { refreshSoon() }

    /// True while a [refreshSoon] is already queued
    private var refreshPending = false

    /**
     * Wire our REPL input bar, filter, refresh and clear actions.
     * Those live in our parent bottom sheet layout, thus we look them up from our root view.
     */
    private fun bindToolBar(view: View) {
        val root = view.rootView

        input = root.findViewById(R.id.consoleInput)
        input?.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSend) {
                runInput()
                true
            } else {
                false
            }
        }

        root.findViewById<ImageButton>(R.id.consoleRun)?.setOnClickListener { runInput() }
        root.findViewById<ImageButton>(R.id.consoleRefresh)?.setOnClickListener { refresh() }
        root.findViewById<ImageButton>(R.id.consoleClear)?.setOnClickListener { clearConsole() }

        root.findViewById<Spinner>(R.id.consoleFilter)?.apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                filterLevels.map { levelLabel(it) }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    filterLevel = filterLevels.getOrNull(position)
                    rebuild()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    /**
     * Human readable label for one of our console message levels.
     */
    private fun levelLabel(level: ConsoleMessage.MessageLevel?): String = when (level) {
        null -> getString(R.string.console_filter_all)
        ConsoleMessage.MessageLevel.ERROR -> getString(R.string.console_level_error)
        ConsoleMessage.MessageLevel.WARNING -> getString(R.string.console_level_warning)
        ConsoleMessage.MessageLevel.LOG -> getString(R.string.console_level_log)
        ConsoleMessage.MessageLevel.TIP -> getString(R.string.console_level_tip)
        ConsoleMessage.MessageLevel.DEBUG -> getString(R.string.console_level_debug)
    }

    /**
     * Snapshot the console messages of the current tab and display them.
     */
    private fun refresh() {
        messages = currentTab()?.getConsoleMessages() ?: emptyList()
        rebuild()
    }

    /**
     * Evaluate the expression currently in our input bar in the current page.
     */
    private fun runInput() {
        val field = input ?: return
        val code = field.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            return
        }

        val webView = currentTab()?.webView
        if (webView == null) {
            addReplEntry(ReplEntry(code, getString(R.string.console_repl_no_page)))
            return
        }

        field.setText("")
        webView.evaluateJavascript(code) { result ->
            // Our callback is invoked on the UI thread, but we may have been dismissed by then
            if (isAdded) {
                addReplEntry(ReplEntry(code, result ?: "null"))
            }
        }
    }

    /**
     * Append a REPL evaluation to our list and scroll to it.
     */
    private fun addReplEntry(entry: ReplEntry) {
        replEntries.add(entry)
        rebuild()
        lastEntry?.let { preference -> view?.post { scrollToPreference(preference) } }
    }

    /**
     * Forget all console messages for the current page, ours included.
     */
    private fun clearConsole() {
        currentTab()?.clearConsoleMessages()
        messages = emptyList()
        replEntries.clear()
        rebuild()
    }

    /**
     * Rebuild our list of preferences from scratch, applying our filter.
     */
    private fun rebuild() {
        val screen = preferenceScreen ?: return

        dynamicPreferences.forEach { screen.removePreference(it) }
        dynamicPreferences.clear()
        lastEntry = null

        val errors = messages.count { it.consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR }
        val warnings = messages.count { it.consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.WARNING }

        findPreference<Preference>(getString(R.string.pref_key_back))?.apply {
            isVisible = true
            summary = getString(R.string.pref_category_summary_console, messages.size, errors, warnings)
        }

        if (messages.isEmpty() && replEntries.isEmpty()) {
            addDynamic(x.Preference(requireContext()).apply {
                summary = getString(R.string.pref_summary_console_empty)
                isIconSpaceReserved = false
            })
            return
        }

        val category = PreferenceCategory(requireContext()).apply { isIconSpaceReserved = false }
        addDynamic(category)

        // Newest first, our own evaluations and the page messages alike: the last thing that
        // happened is what the user came to look at. The requests panel sorts the same way.
        val replPreferences = replEntries.asReversed().mapIndexed { index, entry -> replPreference(index, entry) }
        lastEntry = replPreferences.firstOrNull()
        replPreferences.forEach { category.addPreference(it) }

        val visible = if (filterLevel == null) {
            messages
        } else {
            messages.filter { it.consoleMessage.messageLevel() == filterLevel }
        }
        visible.asReversed().forEachIndexed { index, message -> category.addPreference(messagePreference(index, message)) }
    }

    /**
     * Register a preference we own so that we can remove it on our next rebuild.
     */
    private fun addDynamic(preference: Preference) {
        preferenceScreen.addPreference(preference)
        dynamicPreferences.add(preference)
    }

    /**
     * One console message as an expandable preference.
     */
    private fun messagePreference(index: Int, timestampedMessage: WebPageTab.ConsoleMessage): Preference {
        val message = timestampedMessage.consoleMessage
        return x.Preference(requireContext()).apply {
            key = "console_message_$index"
            isSingleLineTitle = true
            title = TIME_FORMAT.format(Date(timestampedMessage.timestamp))
            summary = message.message()
            verticalPadding = 4.px
            minHeight = 0
            isSingleLineSummary = false
            summaryEllipsize = TextUtils.TruncateAt.END
            isIconSpaceReserved = false
            summaryMaxLines = COLLAPSED_SUMMARY_LINES

            titleDrawableStart = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> R.drawable.ic_error_outline
                ConsoleMessage.MessageLevel.WARNING -> R.drawable.ic_warning_outline
                ConsoleMessage.MessageLevel.DEBUG -> R.drawable.ic_bug_report_outline
                ConsoleMessage.MessageLevel.TIP -> R.drawable.ic_info
                ConsoleMessage.MessageLevel.LOG -> R.drawable.ic_info
                else -> R.drawable.ic_info
            }

            isCopyingEnabled = true
            setOnPreferenceClickListener { toggleExpanded(this); true }
        }
    }

    /**
     * One of our REPL evaluations as an expandable preference.
     */
    private fun replPreference(index: Int, entry: ReplEntry): Preference = x.Preference(requireContext()).apply {
        key = "console_repl_$index"
        isSingleLineTitle = false
        title = "> ${entry.code}"
        summary = entry.result
        verticalPadding = 4.px
        minHeight = 0
        isSingleLineSummary = false
        summaryEllipsize = TextUtils.TruncateAt.END
        isIconSpaceReserved = false
        summaryMaxLines = COLLAPSED_SUMMARY_LINES
        titleDrawableStart = R.drawable.ic_terminal_outline
        isCopyingEnabled = true
        setOnPreferenceClickListener { toggleExpanded(this); true }
    }

    /**
     * Show a long message in full, or fold it back to a few lines.
     */
    private fun toggleExpanded(preference: x.Preference) {
        preference.summaryMaxLines = if (preference.summaryMaxLines == COLLAPSED_SUMMARY_LINES) {
            EXPANDED_SUMMARY_LINES
        } else {
            COLLAPSED_SUMMARY_LINES
        }
    }

    companion object {
        private const val COLLAPSED_SUMMARY_LINES = 3
        private const val EXPANDED_SUMMARY_LINES = 100
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
}
