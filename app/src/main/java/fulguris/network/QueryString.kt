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

package fulguris.network

import java.net.URLDecoder

/**
 * The parameters of [query], decoded name and value pairs, in the order they appear.
 *
 * A list of pairs rather than a map on purpose: a query is not a map. `tag=a&tag=b` and
 * `page=1&page=2` carry every one of their values, and asking `Uri.getQueryParameter` for only the
 * first of them is how a network inspector ends up lying about what went over the wire.
 *
 * [query] is expected to be the raw, still encoded query, as `Uri.getEncodedQuery` gives it:
 * decoding is done here so that a `%` in the URL can never be mistaken for a literal.
 */
fun parseQueryParameters(query: String?): List<Pair<String, String>> =
    query
        ?.split('&')
        ?.filter { it.isNotEmpty() }
        ?.map { parameter ->
            // A parameter without an '=' is a name with an empty value, not a malformed one.
            // Everything past the first '=' belongs to the value, encoded '=' and all.
            val separator = parameter.indexOf('=')
            if (separator < 0) {
                decode(parameter) to ""
            } else {
                decode(parameter.substring(0, separator)) to decode(parameter.substring(separator + 1))
            }
        }
        ?: emptyList()

/**
 * [value] decoded, or left alone when it holds a stray '%' no decoder can make sense of.
 *
 * A broken parameter is worth showing as it was written: this is a debug tool, and hiding the
 * malformed part of a URL would be exactly the wrong thing to do with it.
 */
private fun decode(value: String): String =
    try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }
