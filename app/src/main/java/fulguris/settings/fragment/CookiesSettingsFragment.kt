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
import fulguris.activity.WebBrowserActivity
import fulguris.di.UserPrefs
import fulguris.extensions.launch
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import fulguris.app
import fulguris.extensions.onConfigurationChange
import fulguris.extensions.toast
import fulguris.utils.CookieFields
import fulguris.utils.WebUtils
import fulguris.utils.buildSetCookie
import fulguris.utils.parseCookieFields
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber
import javax.inject.Inject


@AndroidEntryPoint
class CookiesSettingsFragment : AbstractSettingsFragment() {

    @Inject
    @UserPrefs
    internal lateinit var preferences: SharedPreferences

    private var catCookies: PreferenceCategory? = null
    private var deleteAll: Preference? = null


    /**
     * See [AbstractSettingsFragment.titleResourceId]
     */
    override fun titleResourceId(): Int {
        return R.string.pref_title_cookies
    }

    // Set to the domain settings the user last opened
    var domain: String = app.domain


    override fun providePreferencesXmlResource() =
        R.xml.preference_cookies

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        // TODO: Have a custom preference with test input to filter out our list

        // Disable ordering as added to sort alphabetically by title
        catCookies = findPreference<x.PreferenceCategory>(resources.getString(R.string.pref_key_cat_cookies))?.apply { isOrderingAsAdded = true }


        deleteAll = clickablePreference(
            preference = getString(R.string.pref_key_delete_page_cookies),
            onClick = {
                // Show confirmation dialog and proceed if needed
                MaterialAlertDialogBuilder(requireContext())
                    .setCancelable(true)
                    .setTitle(R.string.question_delete_all_page_cookies)
                    //.setMessage(R.string.prompt_please_confirm)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        deleteAllPageCookies()
                    }
                    .launch()

                true
            }
        )

        clickablePreference(
            preference = getString(R.string.pref_key_add_cookie),
            onClick = {
                currentPageUrl()?.let { showCookieDialog(it, null) }
                true
            }
        )
    }

    /**
     *
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        populateCookieList()
    }

    /**
     * Try that stuff against http://setcookie.net which conveniently allows you to specify a path too
     */
    private fun deleteAllPageCookies() {
        Timber.v("Domain: $domain")
        (requireActivity() as? WebBrowserActivity)?.let { browser ->
            browser.tabsManager.currentTab?.url?.let { url ->
                Timber.d("URL: $url")
                val httpUrl = url.toHttpUrl()
                val paths = httpUrl.encodedPathSegments.scan("") { prefix, segment -> "$prefix/$segment" }
                Timber.d("Paths: $paths")
                val domains = ArrayList<String>()
                var host = httpUrl.host
                domains.add(host)
                val tld = httpUrl.topPrivateDomain()
                if (tld!=null) {
                    // Not an IP address great
                    while (host!=tld) {
                        host = host.substringAfter('.')
                        domains.add(host)
                    }
                    //domains.add(tld)
                }
                // Needed to delete all cookies without domain specified
                domains.add("")
                Timber.d("Domains: $domains")

                // Build our list of cookies
                val cm = CookieManager.getInstance()
                val cookies = WebUtils.getCookies(url)
                Timber.v("Cookies count: ${cookies.count()}")
                cookies.forEach { cookie ->
                    Timber.v(cookie.trim())

                    val attrs = cookie.split(";").map { it.split("=", limit=2) }.map { it[0].trim() to if (it.count()==2) it[1].trim() else null }
                    val name = attrs[0].first
                    val value = attrs[0].second
                    val pathAttr = attrs.find { it.first.equals("path",true)  }
                    val domainAttr = attrs.find { it.first.equals("domain",true)  }

                    // If we have a path attribute we don't need to use brut force
                    if (pathAttr==null || domainAttr==null) {
                    //if (true) {
                        // Use legacy brut force way to delete cookies
                        // See: https://stackoverflow.com/q/35390590/3969362
                        // In our chain of domains delete all cookies on our path
                        // That's the only way to make sure we hit the cookie cause we can't tell where it was defined
                        // See: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie
                        // We can't delete cookies with __Secure- and __Host- prefixes
                        // I wonder how that works with our WebkitCookieManager?
                        domains.forEach { domain ->
                            paths.forEach { path ->
                                val delCookie = "$name=;Domain=$domain;Path=$path;Max-Age=0"
                                Timber.d("Delete cookie: $delCookie")
                                cm.setCookie(url,delCookie) {
                                    Timber.v("Cookie $name deleted: $it")
                                }
                            }
                        }
                    } else {
                        // New slightly more sane way to delete cookies
                        // Do it once without domain as this is needed when a path is specified
                        var delCookie = "$name=;Path=${pathAttr.second};Max-Age=0"
                        Timber.d("Delete cookie: $delCookie")
                        cm.setCookie(url,delCookie) {
                            Timber.v("Cookie $name deleted: $it")
                        }

                        // Do it once with domain as this is needed path is root /
                        delCookie = "$name=;Domain=${domainAttr.second};Path=${pathAttr.second};Max-Age=0"
                        //val delCookie = "$name=;Path=${pathAttr.second};Max-Age=0"
                        Timber.d("Delete cookie: $delCookie")
                        cm.setCookie(url,delCookie) {
                            Timber.v("Cookie $name deleted: $it")
                        }

                        // Just don't ask
                    }
                }

                populateCookieList()
            }
        }
    }


    /**
     * URL of the page whose cookies we are managing, or null when no page is loaded.
     */
    private fun currentPageUrl(): String? =
        (requireActivity() as? WebBrowserActivity)?.tabsManager?.currentTab?.url

    /**
     * Create or edit one cookie. Editing also allows deleting it.
     *
     * Note that our cookie API only reports `Domain` and `Path` when
     * `WebViewFeature.GET_COOKIE_INFO` is supported: without it we can still edit the name and the
     * value, and WebView files the cookie under the current page.
     */
    private fun showCookieDialog(url: String, existing: CookieFields?) {
        val layout = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cookie_item, null)
        val nameField = layout.findViewById<EditText>(R.id.cookieName)
        val valueField = layout.findViewById<EditText>(R.id.cookieValue)
        val domainField = layout.findViewById<EditText>(R.id.cookieDomain)
        val pathField = layout.findViewById<EditText>(R.id.cookiePath)

        nameField.setText(existing?.name.orEmpty())
        valueField.setText(existing?.value.orEmpty())
        domainField.setText(existing?.domain.orEmpty())
        pathField.setText(existing?.path.orEmpty())

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.cookie_item_new_title else R.string.cookie_item_edit_title)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.cookie_item_save) { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    activity?.toast(R.string.cookie_invalid_name)
                    return@setPositiveButton
                }
                val fields = CookieFields(
                    name = name,
                    value = valueField.text?.toString().orEmpty(),
                    domain = domainField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    path = pathField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                )
                // Renaming a cookie is a matter of writing the new one and expiring the old one
                if (existing != null && existing.name != name) {
                    setCookie(url, existing, delete = true)
                }
                setCookie(url, fields, delete = false)
                populateCookieList()
            }

        if (existing != null) {
            builder.setNeutralButton(R.string.cookie_item_delete) { _, _ ->
                setCookie(url, existing, delete = true)
                populateCookieList()
            }
        }

        val dialog = builder.launch()
        // Discard it on screen rotation as it's broken anyway
        layout.onConfigurationChange { dialog.dismiss() }
    }

    /**
     * Write [fields] back to the cookie jar, or expire that cookie when [delete] is set.
     */
    private fun setCookie(url: String, fields: CookieFields, delete: Boolean) {
        val cookie = buildSetCookie(fields, delete)
        Timber.d("${if (delete) "Delete" else "Set"} cookie: $cookie")
        CookieManager.getInstance().setCookie(url, cookie)
        // Make sure the change reaches the cookie store right away
        CookieManager.getInstance().flush()
    }

    /**
     *
     */
    @SuppressLint("CheckResult")
    fun populateCookieList() {

        catCookies?.removeAll()
        var cookiesCount = 0

        currentPageUrl()?.let { url ->
            Timber.d("URL: $url")
            // Build our list of cookies
            val cookies = WebUtils.getCookies(url)

            Timber.v("Cookies count: ${cookies.count()}")
            cookiesCount = cookies.count() ?: 0
            cookies.forEach { cookie ->
                Timber.v(cookie.trim())
                val fields = parseCookieFields(cookie)
                // Create cookie preference
                val pref = x.Preference(requireContext()).apply {
                    // Make sure domains and not reversed domains are shown as titles
                    isSingleLineTitle = false
                    title = fields.name
                    summary = cookieSummary(fields, cookie)
                    // Long pressing copies the raw cookie, tapping edits it
                    isCopyingEnabled = true
                    onPreferenceClickListener = Preference.OnPreferenceClickListener {
                        showCookieDialog(url, fields)
                        true
                    }
                }
                catCookies?.addPreference(pref)
            }
        }

        if (cookiesCount!=0) {
            catCookies?.summary = resources.getQuantityString(R.plurals.pref_summary_cookies, cookiesCount, cookiesCount)
            deleteAll?.isEnabled = true
        } else {
            catCookies?.summary = resources.getString(R.string.pref_summary_cookies)
            deleteAll?.isEnabled = false
        }

    }

    /**
     * Show the cookie value followed by its attributes, one per line.
     */
    private fun cookieSummary(fields: CookieFields, cookie: String): String {
        val attributes = cookie.split(';')
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return (listOf(fields.value) + attributes).joinToString("\n")
    }
}
