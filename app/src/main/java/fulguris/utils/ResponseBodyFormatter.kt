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

import java.nio.charset.Charset
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Charset we fall back on when the response advertised none we can use.
 */
private const val DEFAULT_CHARSET = "UTF-8"

/**
 * How a captured response body can be presented to the user.
 */
enum class BodyKind { TEXT, JSON, IMAGE, BINARY }

/**
 * A captured response body prepared for display. [text] is only set for textual bodies, and holds
 * the pretty printed document for [BodyKind.JSON].
 */
data class FormattedBody(val kind: BodyKind, val text: String? = null)

/**
 * Decide how to display a captured response body, decoding it when it is textual.
 *
 * JSON documents are pretty printed for readability, falling back to the raw text when they do not
 * parse. Images are left for the caller to decode into a bitmap. Anything else is reported as
 * binary so the caller can offer the raw bytes instead.
 */
fun formatBody(bytes: ByteArray, mimeType: String?, charset: String? = null): FormattedBody {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    if (mime.startsWith("image/")) {
        return FormattedBody(BodyKind.IMAGE)
    }

    val isJson = mime == "application/json" || mime == "text/json" || mime.endsWith("+json")
    val isText = isJson || mime.startsWith("text/") || mime.endsWith("+xml") ||
            mime == "application/xml" || mime == "application/javascript" ||
            mime == "application/x-javascript" || mime == "application/x-www-form-urlencoded"

    if (!isText) {
        return FormattedBody(BodyKind.BINARY)
    }

    val text = decodeText(bytes, charset)
    if (isJson) {
        prettyPrintJson(text)?.let { return FormattedBody(BodyKind.JSON, it) }
    }
    return FormattedBody(BodyKind.TEXT, text)
}

/**
 * Pretty print a JSON document with a two space indent, or return null when it does not parse.
 */
fun prettyPrintJson(text: String): String? {
    val trimmed = text.trim()
    val first = trimmed.firstOrNull() ?: return null
    return try {
        when (first) {
            '{' -> JSONObject(trimmed).toString(2)
            '[' -> JSONArray(trimmed).toString(2)
            else -> null
        }
    } catch (e: Exception) {
        Timber.v(e, "Response body is not valid JSON")
        null
    }
}

/**
 * How many bytes of a binary body we render as a hex dump, plenty for a dialog.
 */
private const val HEX_DUMP_MAX_BYTES = 1024

/**
 * Render the first bytes of a binary body as the usual `offset  hex  ASCII` dump.
 */
fun hexDump(bytes: ByteArray, maxBytes: Int = HEX_DUMP_MAX_BYTES): String {
    val count = minOf(bytes.size, maxBytes)
    val builder = StringBuilder()
    var offset = 0
    while (offset < count) {
        val line = minOf(16, count - offset)
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (i in 0 until line) {
            val b = bytes[offset + i].toInt() and 0xFF
            hex.append(String.format(Locale.US, "%02X ", b))
            ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        builder.append(String.format(Locale.US, "%08X  %-48s %s", offset, hex.toString(), ascii))
        if (offset + line < count) {
            builder.append('\n')
        }
        offset += line
    }
    return builder.toString()
}

/**
 * Decode a body as text, falling back to UTF-8 when the advertised charset is unknown to us.
 */
private fun decodeText(bytes: ByteArray, charset: String?): String {
    val fallback = Charset.forName(DEFAULT_CHARSET)
    return try {
        val cs = charset?.takeIf { it.isNotEmpty() }?.let { Charset.forName(it) } ?: fallback
        String(bytes, cs)
    } catch (e: Exception) {
        String(bytes, fallback)
    }
}
