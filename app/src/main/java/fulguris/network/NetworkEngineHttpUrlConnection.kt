/*
 * The contents of this file are subject to the Common Public Attribution License Version 1.0.
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * https://github.com/Slion/Fulguris/blob/main/LICENSE.CPAL-1.0.
 */

package fulguris.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network engine that uses HttpURLConnection to handle all network requests.
 * This is Java's standard HTTP client - simple, lightweight, and available on all Android versions.
 *
 * **Known Limitations:**
 * - Does not work with YouTube (use OkHttp or WebView instead)
 * - No HTTP/2 support (only HTTP/1.1)
 * - Limited connection pooling compared to OkHttp
 * - May have issues with some modern websites that require advanced HTTP features
 * - **No HTTP caching configured** (could be added with HttpResponseCache)
 * - POST/PUT/PATCH requests are delegated to WebView (Android API doesn't provide request body)
 *
 * **Caching:**
 * - No cache currently configured
 * - Every request hits the network (slower performance)
 * - Could add HttpResponseCache if needed
 *
 * **Limitations:**
 * - POST/PUT/PATCH requests are delegated to WebView (Android API doesn't provide request body)
 * - Only GET, HEAD, DELETE, and OPTIONS requests are handled by HttpURLConnection
 *
 * **Best for:** Simple websites, testing, or when you want minimal dependencies
 */
class NetworkEngineHttpUrlConnection : NetworkEngine {

    override val displayName: String = "HttpURLConnection"

    override val id: String = "httpurlconnection"

    override fun handleRequest(request: WebResourceRequest): WebResourceResponse? {
        var connection: HttpURLConnection? = null
        try {
            // Check if we can handle this request (scheme and method checks)
            if (!canHandleRequest(request)) {
                return null
            }

            var urlString = request.url.toString()
            var responseCode = 0

            for (hop in 0..MAX_REDIRECTS) {

                // Open connection
                val current = URL(urlString).openConnection() as HttpURLConnection
                connection = current

                // Set request method (GET/HEAD/DELETE/OPTIONS)
                current.requestMethod = request.method

                // Set timeouts
                current.connectTimeout = 10000 // 10 seconds
                current.readTimeout = 30000 // 30 seconds

                // We follow redirects ourselves, see below
                current.instanceFollowRedirects = false

                // Copy headers from WebResourceRequest
                request.requestHeaders?.forEach { (key, value) ->
                    current.setRequestProperty(key, value)
                }

                // WebView does not give us the cookies of the request it asked us to load, our
                // interception running before its network stack attaches them
                cookiesFor(urlString)?.let { current.setRequestProperty("Cookie", it) }

                // Connect and get response
                current.connect()
                responseCode = current.responseCode

                // Follow redirects ourselves: HttpURLConnection would replay every header of ours,
                // cookies included, on whatever host the redirect points at, and cookies are host
                // scoped. Asking for them one hop at a time also means the cookies a hop sets are
                // there for the next one, which login flows rely on.
                val location = if (responseCode in 300..399) current.getHeaderField("Location") else null
                val redirectUrl = location?.let { absoluteUrl(urlString, it) }
                if (redirectUrl == null) {
                    // Not a redirect, or one we are not meant to follow: that response is our
                    // response
                    break
                }
                if (hop == MAX_REDIRECTS) {
                    Timber.w("HttpURLConnection giving up after $MAX_REDIRECTS redirects: $urlString")
                    break
                }

                // Cookies set on the way belong to the host we are leaving
                processCookies(urlString, current.headerFields["Set-Cookie"].orEmpty().filterNotNull())
                current.disconnect()
                connection = null
                urlString = redirectUrl
            }

            // We now hold the response of the last hop
            val conn = connection ?: return null

            if (responseCode < 200 || responseCode >= 400) {
                Timber.w("HttpURLConnection request failed: $responseCode ${request.url}")
                return null // Let WebView handle failures
            }

            // Extract response data
            val contentType = conn.contentType
            val encoding = contentType?.let { extractCharset(it) } ?: "UTF-8"
            val mimeType = contentType?.let { extractMimeType(it) } ?: "text/plain"

            // Build response headers map
            val responseHeaders = mutableMapOf<String, String>()
            conn.headerFields.forEach { (key, values) ->
                // Skip null keys (status line) and empty value lists
                if (key != null && values.isNotEmpty()) {
                    responseHeaders[key] = values.joinToString(", ")
                }
            }

            // Get input stream
            val inputStream = try {
                conn.inputStream
            } catch (e: Exception) {
                // If we can't get the input stream, return empty response
                ByteArrayInputStream(ByteArray(0))
            }

            // Process Set-Cookie headers using base interface method
            // Those belong to the URL we ended up on, not to the one we were asked for
            val setCookieHeaders = conn.headerFields["Set-Cookie"]
            if (!setCookieHeaders.isNullOrEmpty()) {
                processCookies(urlString, setCookieHeaders.filterNotNull())
            }

            // Create WebResourceResponse
            // HTTP/2 responses may have empty message, but Android requires non-empty reason phrase
            val reasonPhrase = conn.responseMessage?.ifEmpty { getDefaultReasonPhrase(responseCode) }
                ?: getDefaultReasonPhrase(responseCode)

            return WebResourceResponse(
                mimeType,
                encoding,
                responseCode,
                reasonPhrase,
                responseHeaders,
                inputStream
            )

        } catch (e: Exception) {
            Timber.e(e, "HttpURLConnection engine error for ${request.url}")
            // Return null to let WebView handle the request normally
            return null
        } finally {
            // Note: We don't disconnect here because the inputStream is still being read
            // The connection will be automatically closed when the stream is closed
        }
    }

    /**
     * Extract MIME type from Content-Type header.
     * Example: "text/html; charset=utf-8" -> "text/html"
     */
    private fun extractMimeType(contentType: String): String {
        return contentType.split(";").firstOrNull()?.trim() ?: "text/plain"
    }

    /**
     * Extract charset from Content-Type header.
     * Example: "text/html; charset=utf-8" -> "utf-8"
     */
    private fun extractCharset(contentType: String): String? {
        return contentType.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
    }
}

/**
 * How many redirect hops [NetworkEngineHttpUrlConnection] follows before handing the last response
 * of the chain to WebView. Matches what OkHttp allows our other engine.
 */
private const val MAX_REDIRECTS = 20

/**
 * Absolute URL [location] points at from [from], null when it is not a redirect we may follow.
 *
 * A Location is usually relative, it then resolves against the URL that provided it.
 */
internal fun absoluteUrl(from: String, location: String): String? {
    return try {
        val url = URL(URL(from), location)
        val scheme = url.protocol
        if (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) {
            url.toString()
        } else {
            // Something like javascript: or mailto:, not ours to follow
            null
        }
    } catch (e: Exception) {
        // A Location we cannot make sense of, let WebView deal with it
        null
    }
}

