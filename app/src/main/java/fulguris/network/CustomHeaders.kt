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

/**
 * A single request header the user asks us to send for a given domain.
 */
data class CustomHeader(val name: String, val value: String)

/**
 * Parse the `Name: Value` lines the user typed in our header editor.
 *
 * Blank lines and lines starting with `#` are ignored, so a user can comment out a header instead
 * of deleting it. A line without a colon is ignored too rather than reported: the editor is a text
 * field and half typed input is the normal case while editing.
 */
fun parseCustomHeaders(raw: String): List<CustomHeader> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            // A name is required, so the colon cannot be the first character
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).trim()
            if (name.isEmpty()) return@mapNotNull null
            CustomHeader(name, line.substring(separator + 1).trim())
        }
        .toList()

/**
 * Serialize headers back to the text form [parseCustomHeaders] reads.
 *
 * A header value cannot contain a newline, so one header per line round trips safely.
 */
fun serializeCustomHeaders(headers: List<CustomHeader>): String =
    headers.joinToString("\n") { "${it.name}: ${it.value}" }

/**
 * Turn our headers into a map, a name given twice keeping its last value.
 */
fun customHeaderMap(headers: List<CustomHeader>): Map<String, String> {
    val map = LinkedHashMap<String, String>(headers.size)
    headers.forEach { map[it.name] = it.value }
    return map
}

/**
 * [base] headers with [custom] applied on top.
 *
 * A custom header replaces any base header sharing its name, case insensitively as HTTP header
 * names are: sending both `Authorization` and `authorization` would be a bug, not a merge.
 */
fun mergeHeaders(base: Map<String, String>, custom: Map<String, String>): MutableMap<String, String> {
    val merged = LinkedHashMap<String, String>(base.size + custom.size)
    merged.putAll(base)
    custom.forEach { (name, value) ->
        merged.keys.filter { it.equals(name, ignoreCase = true) }.forEach { merged.remove(it) }
        merged[name] = value
    }
    return merged
}
