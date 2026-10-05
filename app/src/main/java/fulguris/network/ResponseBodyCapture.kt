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

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.SequenceInputStream
import timber.log.Timber

/**
 * How much of a single response body we keep in memory for the network inspector.
 */
const val MAX_BODY_CAPTURE = 512 * 1024

/**
 * How much captured response body we keep for a page before dropping the oldest captures.
 */
const val MAX_CAPTURED_BODIES_SIZE = 8 * 1024 * 1024L

/**
 * A response body prefix kept in memory for the network inspector.
 */
class CapturedBody(val bytes: ByteArray, val truncated: Boolean) {
    val size: Int get() = bytes.size
}

/**
 * A captured body along with the stream carrying the whole body, to hand back to WebView.
 */
class BodyCapture(val body: CapturedBody, val stream: InputStream)

/**
 * Read at most [cap] bytes of [input] into memory and rebuild a stream carrying the whole body.
 *
 * WebView still needs the full response, so [BodyCapture.stream] is our captured prefix followed
 * by the untouched remainder. A [PushbackInputStream] lets us peek one byte past the cap: that
 * tells us whether the body was truncated without swallowing that byte, and without draining or
 * closing the stream (the engines deliberately keep it open for WebView to read, see
 * `NetworkEngineHttpUrlConnection.handleRequest`).
 *
 * A read error leaves us with whatever we captured so far; the stream is still returned, so WebView
 * fails on the body exactly as it would have without us.
 */
fun captureBody(input: InputStream, cap: Int = MAX_BODY_CAPTURE): BodyCapture {
    val pushback = PushbackInputStream(input, 1)
    val buffer = ByteArray(cap)
    var size = 0
    var truncated = false

    try {
        while (size < cap) {
            val read = pushback.read(buffer, size, cap - size)
            if (read <= 0) {
                break
            }
            size += read
        }
        if (size == cap) {
            val extra = pushback.read()
            if (extra >= 0) {
                truncated = true
                pushback.unread(extra)
            }
        }
    } catch (e: IOException) {
        Timber.w(e, "Could not capture response body, keeping what we read so far")
    }

    val bytes = buffer.copyOf(size)
    return BodyCapture(
        body = CapturedBody(bytes, truncated),
        stream = SequenceInputStream(ByteArrayInputStream(bytes), pushback)
    )
}
