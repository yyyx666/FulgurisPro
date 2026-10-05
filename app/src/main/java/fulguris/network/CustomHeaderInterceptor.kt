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

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Fetch a request ourselves so that every resource of a domain can carry its custom headers.
 *
 * WebView only lets us add headers to the main document, the ones passed to `loadUrl`. The
 * subresources it loads on its own - scripts, styles, images, XHR - never see them and there is no
 * API to change that. The only way through is to intercept those requests and issue them from
 * here, headers and all, which is what this does.
 *
 * Cookies are read from WebView's [CookieManager] and put back on the request. That is what keeps
 * the user logged in: unlike our pluggable engines we are not a separate HTTP stack talking past
 * the WebView cookie jar.
 *
 * Requests carrying a body are left to WebView, see [canIntercept].
 */
class CustomHeaderInterceptor(
    private val client: OkHttpClient = sharedClient(),
    private val cookies: CookieJar = WebViewCookieJar()
) {

    /**
     * Issue [request] again with [customHeaders] on top of the headers WebView already had, or
     * return null whenever we should leave that request to WebView.
     *
     * Called from `shouldInterceptRequest`, thus from a background thread where blocking I/O is
     * expected: that is the whole point of the hook.
     */
    fun intercept(request: WebResourceRequest, customHeaders: Map<String, String>): WebResourceResponse? {
        if (!canIntercept(request, customHeaders)) {
            return null
        }

        val url = request.url.toString()
        return try {
            val headers = mergeHeaders(request.requestHeaders, customHeaders)
            // We always want a full response back. A 304 would have to be relayed to WebView and
            // would serve it from its own cache anyway, so ask for the body we are adding headers
            // to. Dropping Accept-Encoding also lets OkHttp pick one and uncompress transparently,
            // sparing us the content encoding handling.
            headers.removeAllIgnoreCase("Accept-Encoding", "If-None-Match", "If-Modified-Since", "If-Range")
            cookies.cookie(url)?.let { headers["Cookie"] = it }

            val okHttpRequest = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                .method(request.method, null)
                .build()

            val response = client.newCall(okHttpRequest).execute()
            val contentType = response.header("Content-Type")
            val responseHeaders = LinkedHashMap<String, String>()
            response.headers.forEach { (name, value) ->
                // Cookies are already in the jar, forwarding a collapsed Set-Cookie would only
                // risk WebView storing a partial one
                if (!name.equals("Set-Cookie", ignoreCase = true)) {
                    responseHeaders[name] = value
                }
            }
            response.headers("Set-Cookie").takeIf { it.isNotEmpty() }?.forEach { cookies.setCookie(url, it) }

            Timber.v("Custom headers: ${response.code} $url")

            WebResourceResponse(
                contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "text/plain",
                contentType?.charsetOf() ?: "UTF-8",
                response.code,
                response.message.ifEmpty { defaultReasonPhrase(response.code) },
                responseHeaders,
                response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            )
        } catch (e: Exception) {
            // Best effort: better a request without our headers than no resource at all
            Timber.w(e, "Custom headers request failed, leaving it to WebView: $url")
            null
        }
    }

    companion object {
        /**
         * Methods we can issue again, that is the ones without a body: WebView does not hand us
         * the body of a POST, PUT or PATCH, so those keep going through WebView and miss our
         * headers.
         */
        private val BODYLESS_METHODS = setOf("GET", "HEAD", "OPTIONS", "DELETE")

        /**
         * Whether [request] should be reissued with [customHeaders].
         *
         * The main document is excluded: it is covered by the extra headers we pass to `loadUrl`,
         * which keeps redirects and the page history in WebView's hands.
         */
        fun canIntercept(request: WebResourceRequest, customHeaders: Map<String, String>): Boolean {
            if (customHeaders.isEmpty() || request.isForMainFrame) {
                return false
            }
            if (request.url.scheme != "http" && request.url.scheme != "https") {
                return false
            }
            return request.method.uppercase() in BODYLESS_METHODS
        }

        /**
         * One shared client: a per tab or per page one would mean a new connection pool each time.
         */
        private fun sharedClient(): OkHttpClient = SharedClient.client
    }
}

/**
 * Reads and writes the cookies of a request.
 *
 * An interface only so that our tests can stand in for WebView's cookie jar.
 */
interface CookieJar {
    fun cookie(url: String): String?
    fun setCookie(url: String, cookie: String)
}

/**
 * WebView's own cookie jar, the one that holds the user's sessions.
 */
class WebViewCookieJar : CookieJar {
    override fun cookie(url: String): String? = CookieManager.getInstance().getCookie(url)

    override fun setCookie(url: String, cookie: String) {
        CookieManager.getInstance().setCookie(url, cookie)
        CookieManager.getInstance().flush()
    }
}

/**
 * The client we issue custom header requests with, redirects followed so a moved resource still
 * works. Deliberately without a cache: these requests exist to reach the network with our headers.
 */
private object SharedClient {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

/**
 * The `charset` parameter of a Content-Type header, if it advertises one.
 */
internal fun String.charsetOf(): String? =
    split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Drop every header named in [names], ignoring case as HTTP header names do.
 */
private fun MutableMap<String, String>.removeAllIgnoreCase(vararg names: String) {
    names.forEach { name -> keys.filter { it.equals(name, ignoreCase = true) }.forEach { remove(it) } }
}
