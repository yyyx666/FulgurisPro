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

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import fulguris.R
import fulguris.activity.WebBrowserActivity
import fulguris.extensions.launch
import fulguris.extensions.onConfigurationChange
import fulguris.extensions.toast
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber

/**
 * Storage inspector for the current page.
 *
 * Cookies are not enumerable through the platform API, we simply link to our dedicated
 * [CookiesSettingsFragment] for those. Web storage on the other hand can only be read and
 * written from inside the page itself, thus we go through [android.webkit.WebView.evaluateJavascript].
 * IndexedDB and the CacheStorage are not enumerable that way, we only cover `localStorage`
 * and `sessionStorage` of the current origin.
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
     * What the page told us about its web storages.
     */
    private data class StorageDump(
        val origin: String,
        val local: Map<String, String>,
        val session: Map<String, String>
    )

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

    /**
     * Ask the current page for both of its web storages and display the result.
     */
    private fun refresh() {
        val webView = (activity as? WebBrowserActivity)?.tabsManager?.currentTab?.webView
        if (webView == null) {
            render(null)
            return
        }

        webView.evaluateJavascript(DUMP_SCRIPT) { result ->
            // Our callback is invoked on the UI thread, but we may have been dismissed by then
            if (isAdded) {
                render(parseDump(result))
            }
        }
    }

    /**
     * Decode the JSON string our dump script returned.
     * Note that [android.webkit.WebView.evaluateJavascript] hands us the JSON representation of our
     * JavaScript value, hence the double decoding.
     */
    private fun parseDump(result: String?): StorageDump? {
        if (result.isNullOrEmpty() || result == "null") {
            return null
        }

        return try {
            val decoded = JSONTokener(result).nextValue() as? String ?: return null
            val json = JSONObject(decoded)
            StorageDump(
                origin = json.optString(ORIGIN_KEY),
                local = json.optJSONObject("local").toStringMap(),
                session = json.optJSONObject("session").toStringMap()
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not parse storage dump")
            null
        }
    }

    /**
     * Display the provided dump, or our unavailability notice when we have none.
     */
    private fun render(dump: StorageDump?) {
        val local = findPreference<PreferenceCategory>(getString(R.string.pref_key_cat_storage_local)) ?: return
        val session = findPreference<PreferenceCategory>(getString(R.string.pref_key_cat_storage_session)) ?: return

        local.removeAll()
        session.removeAll()

        val back = findPreference<Preference>(getString(R.string.pref_key_back))

        if (dump == null) {
            local.isVisible = false
            session.isVisible = false
            val hasPage = (activity as? WebBrowserActivity)?.tabsManager?.currentTab != null
            back?.summary = getString(
                if (hasPage) R.string.storage_unavailable else R.string.storage_no_page
            )
            return
        }

        local.isVisible = true
        session.isVisible = true

        val origin = dump.origin.takeIf { it.isNotEmpty() && it != "null" }
            ?: getString(R.string.storage_origin_unknown)
        back?.summary = origin

        populate(local, Target.Local, dump.local)
        populate(session, Target.Session, dump.session)
    }

    /**
     * Fill one of our storage categories with one preference per item.
     */
    private fun populate(category: PreferenceCategory, target: Target, items: Map<String, String>) {
        category.summary = resources.getQuantityString(
            R.plurals.pref_summary_storage_items, items.size, items.size
        )

        items.forEach { (key, value) ->
            category.addPreference(x.Preference(requireContext()).apply {
                title = key
                summary = value
                isSingleLineTitle = true
                titleEllipsize = TextUtils.TruncateAt.MIDDLE
                isSingleLineSummary = true
                summaryEllipsize = TextUtils.TruncateAt.MIDDLE
                isIconSpaceReserved = false
                // Long press copies the value, tapping edits the item
                isCopyingEnabled = true
                setOnPreferenceClickListener {
                    showItemDialog(target, key, value)
                    true
                }
            })
        }

        if (items.isEmpty()) {
            category.addPreference(x.Preference(requireContext()).apply {
                summary = getString(R.string.storage_empty)
                isIconSpaceReserved = false
                isSelectable = false
            })
        }

        category.addPreference(actionPreference(R.string.storage_item_add, R.drawable.ic_add) {
            showItemDialog(target, null, null)
        })

        category.addPreference(actionPreference(R.string.storage_item_clear, R.drawable.ic_delete_forever) {
            confirmClear(target)
        })
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
     * Mutate the page storage and refresh our dump once done.
     * Our scripts report an error message, or an empty string on success.
     */
    private fun runScript(script: String) {
        val webView = (activity as? WebBrowserActivity)?.tabsManager?.currentTab?.webView
        if (webView == null) {
            return
        }

        webView.evaluateJavascript(script) { result ->
            if (!isAdded) {
                return@evaluateJavascript
            }

            val error = try {
                JSONTokener(result).nextValue() as? String
            } catch (e: Exception) {
                null
            }

            if (!error.isNullOrEmpty()) {
                Timber.w("Storage script failed: $error")
                activity?.toast(error)
            }

            refresh()
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
     * Enumerate one storage as a map, an inaccessible storage just yields an empty map.
     */
    private fun JSONObject?.toStringMap(): Map<String, String> {
        val json = this ?: return emptyMap()
        val items = LinkedHashMap<String, String>()
        json.keys().forEach { key -> items[key] = json.optString(key) }
        return items
    }

    companion object {
        private const val ORIGIN_KEY = "origin"

        /**
         * Dump both web storages of the current origin as a JSON string.
         */
        private val DUMP_SCRIPT = """
            (function(){
              var dump = function(storage){
                var items = {};
                try {
                  for (var i = 0; i < storage.length; i++) {
                    var key = storage.key(i);
                    items[key] = storage.getItem(key);
                  }
                } catch (e) {}
                return items;
              };
              var origin = '';
              try { origin = window.location.origin || ''; } catch (e) {}
              var local = {}, session = {};
              try { local = dump(window.localStorage); } catch (e) {}
              try { session = dump(window.sessionStorage); } catch (e) {}
              return JSON.stringify({origin: origin, local: local, session: session});
            })()
        """.trimIndent()
    }
}
