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

package fulguris.utils

/**
 * The editable fields of a single cookie.
 *
 * [domain] and [path] are nullable: the legacy cookie API only hands us `name=value`, and even the
 * modern one may omit a domain for host-only cookies.
 */
data class CookieFields(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null
)

/**
 * Parse one cookie string as returned by [WebUtils.getCookies] into its editable fields.
 *
 * The first `;` separated segment holds `name=value`, the remaining ones are attributes we only
 * care about here for `Domain` and `Path` (matched case insensitively, see the parsing done in
 * `CookiesSettingsFragment.deleteAllPageCookies`). Without `WebViewFeature.GET_COOKIE_INFO` the
 * attributes are absent and both come back null.
 */
fun parseCookieFields(cookie: String): CookieFields {
    val attributes = cookie.split(";")
        .map { it.split("=", limit = 2) }
        .map { it[0].trim() to if (it.size == 2) it[1].trim() else null }

    val name = attributes.firstOrNull()?.first.orEmpty()
    val value = attributes.firstOrNull()?.second.orEmpty()

    return CookieFields(
        name = name,
        value = value,
        domain = attributes.firstOrNull { it.first.equals("domain", ignoreCase = true) }?.second,
        path = attributes.firstOrNull { it.first.equals("path", ignoreCase = true) }?.second
    )
}

/**
 * Build the `Set-Cookie` string writing [fields], or expiring it when [delete] is true.
 *
 * Expiring is the only way to remove a cookie through [android.webkit.CookieManager]: you write it
 * back with an empty value and `Max-Age=0`.
 */
fun buildSetCookie(fields: CookieFields, delete: Boolean = false): String {
    val builder = StringBuilder(fields.name).append('=')
    if (!delete) {
        builder.append(fields.value)
    }
    fields.domain?.takeIf { it.isNotEmpty() }?.let { builder.append(";Domain=").append(it) }
    fields.path?.takeIf { it.isNotEmpty() }?.let { builder.append(";Path=").append(it) }
    if (delete) {
        builder.append(";Max-Age=0")
    }
    return builder.toString()
}
