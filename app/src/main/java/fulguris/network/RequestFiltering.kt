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
 * What a request asked for, the categories a desktop browser's network panel sorts them into.
 *
 * Declaration order is the order we offer them in the filter, [OTHER] last: it is where everything
 * we could not name lands.
 */
enum class RequestType { HTML, CSS, JS, XHR, FONTS, IMAGES, MEDIA, WEBSOCKET, OTHER }

/**
 * Sort a request into the category its type filter would show it under.
 *
 * @param mimeType what the server answered with, a `Content-Type` body.
 * @param resourceType what the page asked for, Chromium's `Sec-Fetch-Dest` when it sent one.
 * @param url the request's URL, only read when the two above left us guessing.
 */
fun requestType(mimeType: String?, resourceType: String?, url: String): RequestType {
    // Sorted first because nothing else here can see it: a WebSocket handshake is a GET asking for
    // an upgrade and the 101 answering it carries no content type.
    if (isWebSocket(url, resourceType)) {
        return RequestType.WEBSOCKET
    }

    // Best source first: the server telling us what it sent, the page telling us what it wanted,
    // and last the extension we read off the URL.
    return fromMimeType(mimeType)
        ?: fromFetchDestination(resourceType)
        ?: fromExtension(url)
        ?: RequestType.OTHER
}

/**
 * Whether [url] is one the user is still looking for, given what they typed in the filter field.
 *
 * An empty query - what the field starts as - keeps every request.
 */
fun urlMatchesQuery(url: String, query: String): Boolean {
    val wanted = query.trim()

    return wanted.isEmpty() || url.contains(wanted, ignoreCase = true)
}

private val JAVASCRIPT_MIME_TYPES = setOf(
    "application/ecmascript",
    "application/javascript",
    "application/x-ecmascript",
    "application/x-javascript",
    "text/ecmascript",
    "text/javascript"
)

private val FONT_MIME_TYPES = setOf(
    "application/font-sfnt",
    "application/font-woff",
    "application/vnd.ms-fontobject",
    "application/x-font-otf",
    "application/x-font-ttf",
    "application/x-font-truetype",
    "application/x-font-woff"
)

private val JSON_MIME_TYPES = setOf(
    "application/json",
    "text/json"
)

/**
 * Sort by content type, the server's own answer, the one source that cannot be wrong about what it
 * just sent us.
 */
private fun fromMimeType(mimeType: String?): RequestType? {
    // Parameters are none of our business: `text/html; charset=utf-8` is still text/html
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    return when {
        mime.isEmpty() -> null
        mime == "text/html" || mime == "application/xhtml+xml" -> RequestType.HTML
        mime == "text/css" -> RequestType.CSS
        mime in JAVASCRIPT_MIME_TYPES -> RequestType.JS
        mime.startsWith("image/") -> RequestType.IMAGES
        mime.startsWith("audio/") || mime.startsWith("video/") -> RequestType.MEDIA
        mime.startsWith("font/") || mime in FONT_MIME_TYPES -> RequestType.FONTS
        // A JSON answer is something a script asked for: a page navigating to one would have said
        // `document` as its fetch destination
        mime in JSON_MIME_TYPES || mime.endsWith("+json") -> RequestType.XHR
        else -> null
    }
}

/**
 * Sort by what the page asked for, which is how we know a URL with no extension at all - an API
 * endpoint, a downloaded blob - was fetched rather than navigated to.
 */
private fun fromFetchDestination(resourceType: String?): RequestType? =
    when (resourceType?.trim()?.lowercase()) {
        "document", "frame", "iframe" -> RequestType.HTML
        "style" -> RequestType.CSS
        "script", "worker", "sharedworker", "serviceworker" -> RequestType.JS
        "audioworklet", "paintworklet" -> RequestType.JS
        "image" -> RequestType.IMAGES
        "audio", "video", "track" -> RequestType.MEDIA
        "font" -> RequestType.FONTS
        // Chromium names neither a `fetch` nor an `XMLHttpRequest` any better than that
        "empty", "xhr", "fetch" -> RequestType.XHR
        "websocket" -> RequestType.WEBSOCKET
        else -> null
    }

private fun isWebSocket(url: String, resourceType: String?): Boolean =
    url.startsWith("ws://", ignoreCase = true) ||
        url.startsWith("wss://", ignoreCase = true) ||
        resourceType?.trim()?.equals("websocket", ignoreCase = true) == true

/**
 * Last resort, and the guess most likely to be wrong: the extension of the URL's last segment.
 */
private fun fromExtension(url: String): RequestType? {
    // Query and fragment first: `main.css?v=2` is a CSS file all the same
    val path = url.substringBefore('?').substringBefore('#')
    val extension = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()

    return when (extension) {
        "html", "htm", "xhtml" -> RequestType.HTML
        "css" -> RequestType.CSS
        "js", "mjs", "cjs" -> RequestType.JS
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "avif" -> RequestType.IMAGES
        "woff", "woff2", "ttf", "otf", "eot" -> RequestType.FONTS
        "mp3", "mp4", "m4a", "m4v", "mov", "webm", "ogg", "oga", "ogv", "wav" -> RequestType.MEDIA
        "json" -> RequestType.XHR
        else -> null
    }
}
