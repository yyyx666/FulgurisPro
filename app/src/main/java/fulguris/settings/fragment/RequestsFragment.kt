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

import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import fulguris.R
import fulguris.activity.WebBrowserActivity
import fulguris.extensions.launch
import fulguris.extensions.withSingleChoiceItems
import fulguris.network.RequestType
import fulguris.network.newestFirst
import fulguris.network.requestType
import fulguris.network.urlMatchesQuery
import fulguris.view.WebPageClient

/**
 * Fragment listing every request a page made, newest first.
 *
 * A flat timeline, the way a desktop browser's network panel reads. Grouping by domain would
 * reorder what the page actually did - a script pulled before an image belongs above it, whatever
 * hosts they came from - so the domain moved onto each row instead of becoming a section.
 *
 * The list narrows down three ways: one method, one kind of resource, and a URL fragment typed into
 * the field at the top. Those are the filters worth having where a network panel is one narrow
 * column.
 */
@AndroidEntryPoint
class RequestsFragment : AbstractSettingsFragment() {

    private lateinit var requests: List<WebPageClient.PageRequest>

    /**
     * Everything below the back row, so that a filter change can rebuild the list in place.
     */
    private val dynamicPreferences = mutableListOf<Preference>()

    /**
     * The method we show only, `null` while we show every one of them.
     */
    private var methodFilter: String? = null

    /**
     * The kind of resource we show only, `null` while we show every kind of them.
     */
    private var typeFilter: RequestType? = null

    /**
     * The URL filter field, the one row of ours that outlives a rebuild.
     */
    private lateinit var searchPreference: RequestSearchPreference

    override fun titleResourceId(): Int = R.string.page_requests_title

    override fun providePreferencesXmlResource() = R.xml.preference_page_requests

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        // Get requests from the current tab
        (activity as? WebBrowserActivity)?.tabsManager?.currentTab?.let { tab ->
            requests = tab.webPageClient.getPageRequests()
        } ?: run {
            requests = emptyList()
        }

        // Built here and not declared in `preference_page_requests.xml`: the field only works once
        // its callback is ours, and handing it over at construction is the one wiring that cannot
        // miss. It is then not added through `addDynamic`: that list is what `rebuildRequestsList`
        // empties, and a field rebuilt on every keystroke would lose the cursor and the keyboard
        // along with it.
        searchPreference = RequestSearchPreference(requireContext()).apply {
            onQueryChanged = { rebuildRequestsList() }
        }
        preferenceScreen.addPreference(searchPreference)

        rebuildRequestsList()
    }

    /**
     * Rebuild the list from scratch, filter row included.
     *
     * A filter outlives the requests it was chosen from: the tab keeps loading while this panel is
     * open, and coming back from a request's details can land us on a page whose methods are no
     * longer the same. A filter we have nothing left to show is dropped, rather than kept checked
     * in a dialog that could not render it as selected.
     */
    private fun rebuildRequestsList() {
        dynamicPreferences.forEach { preferenceScreen.removePreference(it) }
        dynamicPreferences.clear()

        val methods = requests.map { it.method }.filter { it.isNotEmpty() }.distinct().sorted()
        if (methodFilter != null && methodFilter !in methods) {
            methodFilter = null
        }

        updateBackSummary()

        // Nothing to search in, nothing to search with
        searchPreference.isVisible = requests.isNotEmpty()

        if (requests.isEmpty()) {
            addEmptyRow()
            return
        }

        addTypeFilterRow()
        addMethodFilterRow(methods)

        // Newest first, applying our filters on a copy: the client keeps its own list in recording
        // order and looks requests up in it by identity, so we never hand it back a reordered one
        val shown = newestFirst(requests, { it.timestamp })
            .filter { methodFilter == null || it.method == methodFilter }
            .filter {
                typeFilter == null || requestType(it.mimeType, it.resourceType, it.url) == typeFilter
            }
            .filter { urlMatchesQuery(it.url, searchPreference.query) }

        if (shown.isEmpty()) {
            addEmptyRow(noMatch = true)
            return
        }

        shown.forEach { addRequestRow(it) }
    }

    /**
     * The back row doubles as our counters, the one place a flat list can report totals.
     */
    private fun updateBackSummary() {
        val blocked = requests.count { it.wasBlocked }

        findPreference<Preference>(getString(R.string.pref_key_back))?.apply {
            isVisible = true
            summary = getString(
                R.string.page_requests_summary,
                requests.size,
                blocked,
                requests.size - blocked
            )
        }
    }

    /**
     * The one row a list with nothing to show gets. [noMatch] tells apart a page that requested
     * nothing from the filters we narrowed its requests away with.
     */
    private fun addEmptyRow(noMatch: Boolean = false) {
        addDynamic(x.Preference(requireContext()).apply {
            summary = getString(
                if (noMatch) R.string.request_no_match else R.string.page_requests_none
            )
            isIconSpaceReserved = false
            isSelectable = false
        })
    }

    /**
     * The type filter row, showing the kind of resource we are down to, or all of them.
     *
     * Every kind is always offered, the way a desktop browser does it: a place that only lists the
     * kinds a page happened to use would not tell the user there was never a WebSocket to find.
     */
    private fun addTypeFilterRow() {
        addDynamic(x.Preference(requireContext()).apply {
            key = "request_type_filter"
            title = getString(R.string.request_filter_type)
            summary = typeFilter?.let { labelOf(it) } ?: getString(R.string.request_filter_all)
            isIconSpaceReserved = false
            isSelectable = true
            setOnPreferenceClickListener {
                showTypeFilterDialog()
                true
            }
        })
    }

    private fun showTypeFilterDialog() {
        val items = listOf<Pair<RequestType?, String>>(
            null to getString(R.string.request_filter_all)
        ) + RequestType.entries.map { it to labelOf(it) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.request_filter_type)
            .apply {
                withSingleChoiceItems(items, typeFilter) { dialog, type ->
                    typeFilter = type
                    dialog.dismiss()
                    rebuildRequestsList()
                }
            }
            .launch()
    }

    /**
     * The user readable name of [type], the one its filter offers it under.
     */
    private fun labelOf(type: RequestType): String = getString(
        when (type) {
            RequestType.HTML -> R.string.request_type_html
            RequestType.CSS -> R.string.request_type_css
            RequestType.JS -> R.string.request_type_js
            RequestType.XHR -> R.string.request_type_xhr
            RequestType.FONTS -> R.string.request_type_fonts
            RequestType.IMAGES -> R.string.request_type_images
            RequestType.MEDIA -> R.string.request_type_media
            RequestType.WEBSOCKET -> R.string.request_type_ws
            RequestType.OTHER -> R.string.request_type_other
        }
    )

    /**
     * The method filter row, showing the method we are down to, or all of them.
     */
    private fun addMethodFilterRow(methods: List<String>) {
        addDynamic(x.Preference(requireContext()).apply {
            key = "request_method_filter"
            title = getString(R.string.request_filter_method)
            summary = methodFilter ?: getString(R.string.request_filter_all)
            isIconSpaceReserved = false
            isSelectable = true
            setOnPreferenceClickListener {
                showMethodFilterDialog(methods)
                true
            }
        })
    }

    /**
     * The methods we can filter on are the ones the page actually used: offering the whole HTTP
     * vocabulary would leave the user picking methods nothing was ever requested with.
     */
    private fun showMethodFilterDialog(methods: List<String>) {
        val items = listOf<Pair<String?, String>>(
            null to getString(R.string.request_filter_all)
        ) + methods.map { it to it }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.request_filter_method)
            .apply {
                withSingleChoiceItems(items, methodFilter) { dialog, method ->
                    methodFilter = method
                    dialog.dismiss()
                    rebuildRequestsList()
                }
            }
            .launch()
    }

    private fun addRequestRow(request: WebPageClient.PageRequest) {
        addDynamic(x.Preference(requireContext()).apply {
            // Use request URL as the key to make it unique
            key = request.url + request.timestamp
            // Path and query both: what a GET asked for is its query
            title = extractPath(request.url)
            isSingleLineTitle = true
            titleEllipsize = TextUtils.TruncateAt.MIDDLE
            // Show domain, method, status and content type, that's what a network inspector is for
            summary = extractNetworkSummary(request)
            isSingleLineSummary = true
            summaryEllipsize = TextUtils.TruncateAt.MIDDLE
            isIconSpaceReserved = false

            // Set drawable start icon based on blocked status
            titleDrawableStart = if (request.wasBlocked) {
                R.drawable.ic_block
            } else {
                R.drawable.ic_check
            }

            // Set encryption icons
            summaryDrawableStart = if (request.url.startsWith("https://", ignoreCase = true)) {
                R.drawable.ic_encrypted_outline
            } else {
                R.drawable.ic_encrypted_off_outline
            }

            // Set fragment navigation with arguments
            fragment = RequestFragment::class.java.name

            // Store request data in extras to pass to the details fragment
            extras.putString("url", request.url)
            extras.putBoolean("was_blocked", request.wasBlocked)
            extras.putLong("timestamp", request.timestamp)
            extras.putString("method", request.method)
            extras.putBoolean("is_main_frame", request.isMainFrame)
            extras.putString("resource_type", request.resourceType)
            extras.putInt("status_code", request.statusCode ?: -1)
            extras.putString("status_text", request.statusText)
            extras.putString("mime_type", request.mimeType)
            extras.putLong("duration_ms", request.durationMs ?: -1L)
            extras.putLong("body_id", request.bodyId ?: -1L)
            extras.putLong("body_size", request.bodySize ?: -1L)
            extras.putBoolean("body_truncated", request.bodyTruncated)
            extras.putLong("request_body_id", request.requestBodyId ?: -1L)
            extras.putLong("request_body_size", request.requestBodySize ?: -1L)
            extras.putBoolean("request_body_truncated", request.requestBodyTruncated)
            extras.putString("error", request.error)
            extras.putString("web_socket_state", request.webSocketState)
            extras.putStringArrayList(
                "request_headers",
                ArrayList(request.requestHeaders.map { "${it.key}: ${it.value}" })
            )
            extras.putStringArrayList(
                "response_headers",
                ArrayList(request.responseHeaders.map { "${it.key}: ${it.value}" })
            )
        })
    }

    private fun addDynamic(preference: Preference) {
        preferenceScreen.addPreference(preference)
        dynamicPreferences.add(preference)
    }

    /**
     * Summarize what we know about a request in one line, its domain first.
     *
     * Falls back to our URL based summary when we have nothing else to show, which is how
     * `file:` and `data:` requests - those without a host - still read as something.
     */
    private fun extractNetworkSummary(request: WebPageClient.PageRequest): String {
        val parts = mutableListOf<String>()

        hostOf(request.url)?.let { parts.add(it) }
        if (request.method.isNotEmpty()) {
            parts.add(request.method)
        }
        // A WebSocket has no status line to show, its lifecycle state is what it has instead
        request.webSocketState?.let { parts.add(it) }
        request.statusCode?.let { parts.add(it.toString()) }
        (request.mimeType ?: request.resourceType)?.let { parts.add(it) }

        return if (parts.isEmpty()) extractSummary(request.url) else parts.joinToString(" · ")
    }

    private fun hostOf(url: String): String? =
        try {
            Uri.parse(url).host
        } catch (e: Exception) {
            null
        }

    /**
     * Extract the path and query from a URL for display as title
     */
    private fun extractPath(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path ?: "/"
            val query = uri.query
            if (query != null && query.isNotEmpty()) {
                "$path?$query"
            } else {
                path
            }
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Extract summary information for display.
     * Priority: 1) URL parameters, 2) Content type category (Script, Style, Image, Font, etc.), 3) URL scheme in uppercase
     */
    private fun extractSummary(url: String): String {
        return try {
            val uri = Uri.parse(url)

            // First priority: return query parameters if they exist
            if (!uri.query.isNullOrEmpty()) {
                return uri.query!!
            }

            // Second priority: extract file type/extension and map to content category
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                val lastSegment = path.substringAfterLast('/')
                if (lastSegment.contains('.')) {
                    val extension = lastSegment.substringAfterLast('.').lowercase()
                    if (extension.isNotEmpty()) {
                        val contentType = getMimeTypeOrExtension(extension)
                        if (contentType != null) {
                            return contentType
                        }
                    }
                }
            }

            // Third priority: return scheme in uppercase
            uri.scheme?.uppercase() ?: " "
        } catch (e: Exception) {
            " "
        }
    }

    /**
     * Get MIME type from file extension using Android's MimeTypeMap.
     * Returns the MIME type if known (e.g., "text/javascript"), otherwise returns the uppercase extension (e.g., "JS")
     */
    private fun getMimeTypeOrExtension(extension: String): String? {
        // Get MIME type from Android's MimeTypeMap
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        val ext = extension.uppercase()

        // Return MIME type if available, otherwise just the extension
        return mimeType ?: ext
    }
}
