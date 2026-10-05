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
 * The Initial Developer of the Original Code is Stéphane Lenclud.
 *
 * All portions of the code written by Stéphane Lenclud are Copyright © 2020 Stéphane Lenclud.
 * All Rights Reserved.
 */

package fulguris.settings.fragment

import fulguris.R
import fulguris.app
import fulguris.extensions.launch
import fulguris.network.CustomHeader
import fulguris.network.serializeCustomHeaders
import fulguris.settings.preferences.DomainPreferences
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import fulguris.extensions.onConfigurationChange
import fulguris.extensions.toast
import timber.log.Timber


/**
 * Edit the custom request headers of the domain we are in the settings of.
 *
 * Those headers are sent with every request that domain makes, see
 * [fulguris.network.CustomHeaderInterceptor] for the resources and [fulguris.view.WebPageTab] for
 * the main document.
 */
@AndroidEntryPoint
class CustomHeadersSettingsFragment : AbstractSettingsFragment() {

    lateinit var prefs: DomainPreferences

    // Capture that as it could change as we open parent settings
    val domain = app.domain

    private var catHeaders: x.PreferenceCategory? = null

    /**
     * See [AbstractSettingsFragment.titleResourceId]
     */
    override fun titleResourceId(): Int {
        return R.string.pref_title_custom_headers
    }

    override fun providePreferencesXmlResource() =
        R.xml.preference_custom_headers

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        prefs = DomainPreferences(requireContext(), domain)
        // Our headers live with the settings of the domain we are editing
        preferenceManager.sharedPreferencesName = DomainPreferences.name(domain)
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        super.onCreatePreferences(savedInstanceState, rootKey)

        // Disable ordering as we list the headers in the order the user wrote them
        catHeaders = findPreference<x.PreferenceCategory>(resources.getString(R.string.pref_key_cat_custom_headers))?.apply { isOrderingAsAdded = true }

        clickablePreference(
            preference = getString(R.string.pref_key_add_header),
            onClick = {
                showHeaderDialog(null)
                true
            }
        )
    }

    /**
     *
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        populateHeaderList()
    }

    /**
     * Create, edit or delete one header, depending on what [existing] is.
     */
    private fun showHeaderDialog(existing: CustomHeader?) {
        val layout = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_header_item, null)
        val nameField = layout.findViewById<EditText>(R.id.headerName)
        val valueField = layout.findViewById<EditText>(R.id.headerValue)

        nameField.setText(existing?.name.orEmpty())
        valueField.setText(existing?.value.orEmpty())

        // Work on a copy: the dialog is cancelled by a rotation and we don't want half an edit
        val headers = prefs.customHeaders.toMutableList()

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.custom_header_item_new_title else R.string.custom_header_item_edit_title)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.custom_header_item_save) { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    activity?.toast(R.string.custom_header_invalid_name)
                    return@setPositiveButton
                }
                // Renaming one is a matter of dropping the old name and adding the new one
                if (existing != null) {
                    headers.remove(existing)
                }
                headers.add(CustomHeader(name, valueField.text?.toString().orEmpty()))
                saveHeaders(headers)
            }

        if (existing != null) {
            builder.setNeutralButton(R.string.custom_header_item_delete) { _, _ ->
                headers.remove(existing)
                saveHeaders(headers)
            }
        }

        val dialog = builder.launch()
        // Discard it on screen rotation as it's broken anyway
        layout.onConfigurationChange { dialog.dismiss() }
    }

    /**
     * Write [headers] back to our domain settings and refresh the list.
     */
    private fun saveHeaders(headers: List<CustomHeader>) {
        val serialized = serializeCustomHeaders(headers)
        Timber.d("Domain $domain custom headers: $serialized")
        prefs.customRequestHeaders = serialized
        populateHeaderList()
    }

    /**
     * List the custom headers of our domain, one preference each.
     *
     * Tapping one edits it, long pressing copies its value.
     */
    private fun populateHeaderList() {

        catHeaders?.removeAll()

        val headers = prefs.customHeaders
        headers.forEach { header ->
            val pref = x.Preference(requireContext()).apply {
                isSingleLineTitle = false
                title = header.name
                summary = header.value
                isCopyingEnabled = true
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    showHeaderDialog(header)
                    true
                }
            }
            catHeaders?.addPreference(pref)
        }

        catHeaders?.summary = if (headers.isEmpty()) {
            resources.getString(R.string.pref_summary_custom_headers_none)
        } else {
            resources.getQuantityString(R.plurals.pref_summary_custom_headers_count, headers.size, headers.size)
        }
    }
}
