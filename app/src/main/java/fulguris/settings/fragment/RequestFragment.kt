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

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import fulguris.R
import fulguris.activity.WebBrowserActivity
import fulguris.extensions.onConfigurationChange
import fulguris.network.CapturedBody
import fulguris.network.NetworkEngineManager
import fulguris.network.parseQueryParameters
import fulguris.utils.BodyKind
import fulguris.utils.Utils
import fulguris.utils.formatBody
import fulguris.utils.hexDump
import fulguris.view.WebPageClient
import java.util.Date

/**
 * Fragment to display detailed information about a page request.
 *
 * One side of the request at a time, a tab bar at the top switching between what we sent and what
 * came back. The two are long enough on their own - headers alone overflow a phone screen - and
 * mixing them is what made the interesting part of a response hard to find.
 */
@AndroidEntryPoint
class RequestFragment : AbstractSettingsFragment() {

    /**
     * What we were told about one request, read from our arguments once.
     */
    private class Details(
        val url: String,
        val wasBlocked: Boolean,
        val timestamp: Long,
        val method: String,
        val isMainFrame: Boolean,
        val resourceType: String?,
        val statusCode: Int,
        val mimeType: String?,
        val durationMs: Long,
        val requestHeaders: List<String>,
        val responseHeaders: List<String>,
        val bodyId: Long,
        val bodySize: Long,
        val bodyTruncated: Boolean
    )

    private lateinit var details: Details

    /**
     * Everything below the back row and the tabs, so that switching tabs can rebuild them.
     */
    private val dynamicPreferences = mutableListOf<Preference>()

    private var currentPane = RequestTabsPreference.Pane.REQUEST

    override fun titleResourceId(): Int = R.string.page_request_details_title

    override fun providePreferencesXmlResource() = R.xml.preference_page_request_details

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        // Get request details from arguments (passed from preference extras)
        details = Details(
            url = arguments?.getString("url") ?: "",
            wasBlocked = arguments?.getBoolean("was_blocked", false) ?: false,
            timestamp = arguments?.getLong("timestamp", 0L) ?: 0L,
            method = arguments?.getString("method") ?: "",
            isMainFrame = arguments?.getBoolean("is_main_frame", false) ?: false,
            resourceType = arguments?.getString("resource_type"),
            statusCode = arguments?.getInt("status_code", -1) ?: -1,
            mimeType = arguments?.getString("mime_type"),
            durationMs = arguments?.getLong("duration_ms", -1L) ?: -1L,
            requestHeaders = arguments?.getStringArrayList("request_headers") ?: arrayListOf(),
            responseHeaders = arguments?.getStringArrayList("response_headers") ?: arrayListOf(),
            bodyId = arguments?.getLong("body_id", -1L) ?: -1L,
            bodySize = arguments?.getLong("body_size", -1L) ?: -1L,
            bodyTruncated = arguments?.getBoolean("body_truncated", false) ?: false
        )

        setBackSummary(details.url)

        currentPane = restoredPane(savedInstanceState)

        // Built here and not declared in the XML: the switch only works once its callback is ours,
        // and handing it over at construction is the one wiring that cannot miss. It is not added
        // through `addDynamic`: that list is what `renderPane` empties, and the tabs outlive it.
        val tabs = RequestTabsPreference(requireContext()).apply {
            select(currentPane)
            onPaneSelected = { pane ->
                currentPane = pane
                renderPane()
            }
        }
        preferenceScreen.addPreference(tabs)

        renderPane()
    }

    /**
     * The half we were showing, Request when we cannot tell.
     */
    private fun restoredPane(savedInstanceState: Bundle?): RequestTabsPreference.Pane {
        val name = savedInstanceState?.getString(STATE_PANE)
        return RequestTabsPreference.Pane.entries.firstOrNull { it.name == name }
            ?: RequestTabsPreference.Pane.REQUEST
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // A rotation recreates us, and landing back on the request side after reading a response
        // is the kind of small betrayal that makes a panel tiring to use
        outState.putString(STATE_PANE, currentPane.name)
    }

    /**
     * Show the half of the request we are on, and only that one.
     */
    private fun renderPane() {
        dynamicPreferences.forEach { preferenceScreen.removePreference(it) }
        dynamicPreferences.clear()

        when (currentPane) {
            RequestTabsPreference.Pane.REQUEST -> populateRequestPane()
            RequestTabsPreference.Pane.RESPONSE -> populateResponsePane()
        }
    }

    /**
     * Register a preference we own, so that our next tab switch takes it away again.
     */
    private fun addDynamic(preference: Preference) {
        preferenceScreen.addPreference(preference)
        dynamicPreferences.add(preference)
    }

    /**
     * Our back row shows the URL scheme, the shortest thing that still says where we came from.
     */
    private fun setBackSummary(url: String) {
        findPreference<Preference>(getString(R.string.pref_key_back))?.apply {
            isVisible = true
            summary = try {
                Uri.parse(url).scheme ?: url
            } catch (e: Exception) {
                ""
            }
        }
    }

    /**
     * The network client of the tab we are showing a request of.
     */
    private fun webPageClient(): WebPageClient? =
        (activity as? WebBrowserActivity)?.tabsManager?.currentTab?.webPageClient

    /**
     * What we sent: where it went, how, and with which headers.
     */
    private fun populateRequestPane() {
        val url = details.url

        // Open URL preference
        addDynamic(x.Preference(requireContext()).apply {
            title = getString(R.string.action_open)
            summary = url
            isSingleLineSummary = true
            isIconSpaceReserved = true
            setIcon(R.drawable.ic_link)
            intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(requireContext().packageName)
                // Make sure we tell ourselves where this is coming from
                // Avoid sending ourselves to the background when closing this tab
                putExtra("PACKAGE", requireContext().packageName)
            }
        })

        // Status category
        val statusCategory = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.request_status)
            isIconSpaceReserved = false
        }
        addDynamic(statusCategory)

        statusCategory.addPreference(x.Preference(requireContext()).apply {
            title = if (details.wasBlocked) {
                getString(R.string.page_requests_blocked)
            } else {
                getString(R.string.page_requests_allowed)
            }
            isIconSpaceReserved = true
            setIcon(if (details.wasBlocked) R.drawable.ic_block else R.drawable.ic_check)
            isSelectable = false
        })

        populateUrlDetails(url)
        populateNetworkDetails()

        addHeaderCategory(R.string.request_headers, R.string.request_headers_none, details.requestHeaders)

        // Timestamp category
        val timestampCategory = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.request_timestamp)
            isIconSpaceReserved = false
        }
        addDynamic(timestampCategory)

        timestampCategory.addPreference(x.Preference(requireContext()).apply {
            title = getString(R.string.request_timestamp)
            summary = if (details.timestamp > 0) {
                DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(details.timestamp)).toString()
            } else {
                getString(R.string.unknown)
            }
            isIconSpaceReserved = false
            isSelectable = false
        })
    }

    /**
     * Whatever we can pull out of the URL ourselves: the parts of it a user copies out.
     */
    private fun populateUrlDetails(url: String) {
        try {
            val uri = Uri.parse(url)

            // URL Details category
            val detailsCategory = PreferenceCategory(requireContext()).apply {
                title = getString(R.string.request_url)
                isIconSpaceReserved = false
            }
            addDynamic(detailsCategory)

            // Copy URL preference (first in category)
            detailsCategory.addPreference(x.Preference(requireContext()).apply {
                title = getString(R.string.action_copy)
                summary = getString(R.string.request_url_length, url.length)
                isIconSpaceReserved = true
                setIcon(R.drawable.ic_content_copy)
                setOnPreferenceClickListener {
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("URL", url)
                    clipboard.setPrimaryClip(clip)
                    // Show a toast to confirm
                    android.widget.Toast.makeText(requireContext(), R.string.message_text_copied, android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            })

            // Full URL preference
            detailsCategory.addPreference(x.Preference(requireContext()).apply {
                title = getString(R.string.request_url)
                summary = url
                isIconSpaceReserved = false
                isSelectable = false
            })

            // Domain preference
            uri.host?.let { host ->
                detailsCategory.addPreference(x.Preference(requireContext()).apply {
                    title = getString(R.string.request_domain)
                    summary = host
                    isIconSpaceReserved = false
                    isSelectable = false
                })
            }

            // Path preference
            uri.path?.let { path ->
                detailsCategory.addPreference(x.Preference(requireContext()).apply {
                    title = getString(R.string.request_path)
                    summary = path
                    isIconSpaceReserved = false
                    isSelectable = false
                })
            }

            // Every parameter, repeated names included: that is the request, and a plain lookup
            // would only ever show the first value of each name
            uri.encodedQuery?.let { query ->
                val parameters = parseQueryParameters(query)
                if (parameters.isNotEmpty()) {
                    detailsCategory.addPreference(x.Preference(requireContext()).apply {
                        title = getString(R.string.request_parameters)
                        isIconSpaceReserved = false
                        isSelectable = false
                    })
                }
                parameters.forEach { (name, value) ->
                    detailsCategory.addPreference(x.Preference(requireContext()).apply {
                        title = name
                        summary = value
                        isSingleLineSummary = true
                        isIconSpaceReserved = false
                        isCopyingEnabled = true
                        isSelectable = false
                    })
                }
            }
        } catch (e: Exception) {
            // If URL parsing fails, just show the raw URL
            addDynamic(x.Preference(requireContext()).apply {
                title = getString(R.string.request_url)
                summary = url
                isIconSpaceReserved = false
                isSelectable = false
            })
        }
    }

    /**
     * Populate our network section with whatever the network layer was able to report.
     * Response side information is only available when an engine handled the request.
     */
    private fun populateNetworkDetails() {
        val unknown = getString(R.string.request_unknown_value)

        val networkCategory = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.request_network)
            isIconSpaceReserved = false
        }
        addDynamic(networkCategory)

        networkCategory.addPreference(x.Preference(requireContext()).apply {
            title = getString(R.string.request_method)
            summary = if (details.method.isEmpty()) unknown else details.method
            isIconSpaceReserved = false
            isSelectable = false
        })

        networkCategory.addPreference(x.Preference(requireContext()).apply {
            title = getString(R.string.request_type)
            summary = when {
                details.isMainFrame -> getString(R.string.request_main_frame)
                details.resourceType != null -> details.resourceType
                else -> unknown
            }
            isIconSpaceReserved = false
            isSelectable = false
        })

        networkCategory.addPreference(x.Preference(requireContext()).apply {
            title = getString(R.string.request_status)
            summary = if (details.statusCode >= 0) details.statusCode.toString() else unknown
            isIconSpaceReserved = false
            isSelectable = false
        })

        networkCategory.addPreference(x.Preference(requireContext()).apply {
            title = getString(R.string.request_mime)
            summary = details.mimeType ?: unknown
            isIconSpaceReserved = false
            isSelectable = false
        })

        networkCategory.addPreference(x.Preference(requireContext()).apply {
            title = getString(R.string.request_duration)
            summary = if (details.durationMs >= 0) {
                getString(R.string.request_duration_value, details.durationMs)
            } else {
                unknown
            }
            isIconSpaceReserved = false
            isSelectable = false
        })
    }

    /**
     * What came back: the headers it answered with and the body we captured.
     */
    private fun populateResponsePane() {
        addHeaderCategory(
            R.string.request_response_headers,
            R.string.request_headers_none,
            details.responseHeaders
        )
        populateResponseBody()
    }

    /**
     * Add one category listing [headers], one preference each, or the [aEmptySummary] line when we
     * were reported none.
     */
    private fun addHeaderCategory(aTitle: Int, aEmptySummary: Int, headers: List<String>) {
        val category = PreferenceCategory(requireContext()).apply {
            title = getString(aTitle)
            isIconSpaceReserved = false
        }
        addDynamic(category)

        if (headers.isEmpty()) {
            category.addPreference(x.Preference(requireContext()).apply {
                summary = getString(aEmptySummary)
                isIconSpaceReserved = false
                isSelectable = false
            })
            return
        }

        headers.forEach { header ->
            category.addPreference(x.Preference(requireContext()).apply {
                title = header.substringBefore(':')
                summary = header.substringAfter(':').trim()
                isSingleLineSummary = true
                summaryEllipsize = TextUtils.TruncateAt.MIDDLE
                isIconSpaceReserved = false
                isCopyingEnabled = true
                isSelectable = false
            })
        }
    }

    /**
     * Populate our response body section.
     *
     * We only ever capture a body from an engine that handled the request, see
     * `WebPageClient.captureResponseBody`. So when we have none we tell the user why instead of
     * pretending there is nothing to see.
     */
    private fun populateResponseBody() {
        val category = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.request_body)
            isIconSpaceReserved = false
        }
        addDynamic(category)

        val body = details.bodyId.takeIf { it >= 0 }?.let { webPageClient()?.getResponseBody(it) }

        if (body == null) {
            category.addPreference(x.Preference(requireContext()).apply {
                summary = getString(
                    bodyUnavailableReason(
                        details.wasBlocked,
                        details.bodySize,
                        details.method,
                        details.statusCode
                    )
                )
                isIconSpaceReserved = false
                isSelectable = false
            })
            return
        }

        val formatted = formatBody(body.bytes, details.mimeType)
        // A truncated image is not decodable, showing its bytes is all we can do
        val kind = if (body.truncated && formatted.kind == BodyKind.IMAGE) BodyKind.BINARY else formatted.kind

        category.addPreference(x.Preference(requireContext()).apply {
            title = getString(
                when (kind) {
                    BodyKind.IMAGE -> R.string.request_body_preview_image
                    BodyKind.BINARY -> R.string.request_body_raw
                    else -> R.string.request_body_view
                }
            )
            if (formatted.kind == BodyKind.JSON) {
                summary = getString(R.string.request_body_json)
            }
            isIconSpaceReserved = true
            setIcon(
                when (kind) {
                    BodyKind.IMAGE -> R.drawable.ic_image
                    BodyKind.BINARY -> R.drawable.ic_unknown_document_outline
                    else -> R.drawable.ic_code
                }
            )
            setOnPreferenceClickListener {
                showResponseBody(body, kind, formatted.text)
                true
            }
        })

        category.addPreference(x.Preference(requireContext()).apply {
            title = getString(R.string.request_body_size)
            summary = buildSizeSummary(details.bodySize, details.bodyTruncated)
            isIconSpaceReserved = false
            isSelectable = false
        })
    }

    /**
     * Why we have no body for a request, as a string resource.
     *
     * Four different things keep a body from us and conflating them sends the user chasing the
     * wrong one: the request never carried one, no engine was in charge, the engine declined it, or
     * we captured it and dropped it under memory pressure.
     */
    private fun bodyUnavailableReason(
        wasBlocked: Boolean,
        bodySize: Long,
        method: String,
        statusCode: Int
    ): Int {
        val engine = webPageClient()?.networkEngineManager?.getCurrentEngine()
        val engineProvidesBodies = engine != null && engine.id != NetworkEngineManager.DEFAULT_ENGINE_ID

        return when {
            wasBlocked -> R.string.request_body_unavailable_blocked
            // A body size means we did capture one, so it is the eviction that took it from us
            bodySize >= 0 -> R.string.request_body_unavailable_evicted
            isBodylessRequestMethod(method) -> R.string.request_body_unavailable_method
            !engineProvidesBodies -> R.string.request_body_unavailable_webview
            // The engine had the request and still gave us no body, typically a response it could
            // not use, like a 304 or an error
            else -> R.string.request_body_unavailable_engine
        }
    }

    /**
     * Summarize the size of the body we hold, saying so when we only kept a prefix of it.
     */
    private fun buildSizeSummary(bodySize: Long, bodyTruncated: Boolean): String {
        val size = if (bodySize >= 0) {
            Formatter.formatFileSize(requireContext(), bodySize)
        } else {
            getString(R.string.request_unknown_value)
        }
        return if (bodyTruncated) {
            getString(R.string.request_body_truncated, size)
        } else {
            size
        }
    }

    /**
     * Show the captured body in a dialog, formatted the way [kind] calls for.
     */
    private fun showResponseBody(body: CapturedBody, kind: BodyKind, text: String?) {
        val content: View = if (kind == BodyKind.IMAGE) {
            buildImageView(body)
        } else {
            buildTextView(if (kind == BodyKind.BINARY) hexDump(body.bytes) else text.orEmpty())
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (kind == BodyKind.IMAGE) {
                    getString(R.string.request_body_preview_image)
                } else {
                    getString(R.string.request_body)
                }
            )
            .setView(content)
            .setPositiveButton(R.string.action_ok, null)
            .create()

        // Our content was built for the current configuration, dismiss the dialog when it changes
        content.onConfigurationChange { dialog.dismiss() }

        dialog.show()
    }

    /**
     * A scrollable, selectable, monospace view of a body.
     *
     * Wrapped in a plain container: [onConfigurationChange] adds a child of its own to what we hand
     * it and a [ScrollView] only ever accepts one.
     */
    private fun buildTextView(content: String): View {
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        val view = TextView(requireContext()).apply {
            text = content
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(requireContext()).apply {
            addView(view)
        }
        return LinearLayout(requireContext()).apply {
            addView(scroll)
        }
    }

    /**
     * An image view sized to the screen, decoding only as much of the body as we can display.
     */
    private fun buildImageView(body: CapturedBody): View {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(body.bytes, 0, body.bytes.size, options)

        val metrics = resources.displayMetrics
        options.inJustDecodeBounds = false
        options.inSampleSize = Utils.calculateInSampleSize(options, metrics.widthPixels, metrics.heightPixels)

        // Null when what we hold is not an image after all, the MIME type being all we have to go on
        val bitmap = BitmapFactory.decodeByteArray(body.bytes, 0, body.bytes.size, options)

        val content: View = if (bitmap == null) {
            buildTextView(getString(R.string.request_body_not_an_image))
        } else {
            ImageView(requireContext()).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.request_body_preview_image)
            }
        }

        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        return LinearLayout(requireContext()).apply {
            setPadding(padding, padding, padding, padding)
            addView(content)
        }
    }

    /**
     * Methods WebView handles itself, we therefore never get a response body for them.
     * See `NetworkEngine.canHandleRequest`.
     */
    private fun isBodylessRequestMethod(method: String): Boolean =
        method.equals("POST", ignoreCase = true) ||
                method.equals("PUT", ignoreCase = true) ||
                method.equals("PATCH", ignoreCase = true)

    companion object {
        private const val STATE_PANE = "request_pane"
    }
}
