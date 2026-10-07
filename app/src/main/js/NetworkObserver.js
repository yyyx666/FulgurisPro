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

/**
 * Reports fetch and XMLHttpRequest exchanges to our network inspector.
 *
 * For a request WebView issues itself we are blind to two things: `WebResourceRequest`, the only
 * request object the platform hands us, carries no request body, and both network engines therefore
 * hand every body-carrying method (POST, PUT, PATCH) back to WebView to send. That leaves our
 * inspector showing such a request by its URL and method and nothing else - no payload, no status,
 * no timing, no response body.
 *
 * The page's own fetch/XHR are a vantage point we do have, so this wraps both and reports each
 * completed exchange, request body included, through the `_fulgurisNetwork` interface that
 * WebViewEx registers.
 *
 * What this can and cannot see: it reports what page JavaScript can see of its own requests. It
 * cannot see a form submission's page navigation, nor a request the browser issues on the page's
 * behalf (an `<img>`, a stylesheet, a WebSocket handshake), and it cannot see a response body that
 * CORS marked opaque.
 *
 * Must be injected before any page script issues a request, hence from onPageStarted.
 */
(function() {
    'use strict';

    if (window._fulgurisNetworkInstalled) return;
    window._fulgurisNetworkInstalled = true;

    /** Name of the JS interface WebViewEx registers for us, see WebViewEx.NETWORK_JS_INTERFACE. */
    var BRIDGE = '_fulgurisNetwork';

    /** How much of a body we hand over. The inspector applies its own, smaller, cap. */
    var MAX_BODY = 262144;

    /** Content types whose body is worth reading as text rather than summarising as bytes. */
    var TEXTUAL = /^text\/|json|javascript|ecmascript|\+xml|xml|svg|x-www-form-urlencoded/i;

    function report(payload) {
        try {
            var bridge = window[BRIDGE];
            if (bridge) bridge.onNetworkReport(JSON.stringify(payload));
        } catch (e) {
            // Reporting is a debugging aid, it must never break the page
        }
    }

    function clamp(text) {
        if (text === undefined || text === null) return null;
        return String(text).slice(0, MAX_BODY);
    }

    function wasClamped(text) {
        return text !== undefined && text !== null && String(text).length > MAX_BODY;
    }

    /**
     * A request URL in the absolute form the inspector already knows it by.
     *
     * A page usually asks for `/echo`, and that is what fetch and XHR are handed, but the row
     * WebView recorded for the same exchange carries the resolved URL. Reporting the relative form
     * thus matches nothing, and the exchange shows up twice: once as WebView saw it, without a body
     * or a status, and once as our own bare-path row. Resolving against the document base - which
     * honours a `<base href>` - puts the two back on the same footing.
     */
    function absolute(url) {
        try {
            return new URL(url, document.baseURI).href;
        } catch (e) {
            return url;
        }
    }

    function isTextual(contentType) {
        return !!contentType && TEXTUAL.test(contentType);
    }

    /** True for an ArrayBuffer or a typed array view of one. */
    function isBinary(body) {
        return typeof ArrayBuffer !== 'undefined'
            && (body instanceof ArrayBuffer
                || (ArrayBuffer.isView && ArrayBuffer.isView(body)));
    }

    /**
     * A request body as text, for the shapes a page actually sends.
     *
     * A Blob or an ArrayBuffer is summarised rather than read: its bytes are not text, and reading
     * one asynchronously would have us report after the request may already have completed.
     */
    function describeRequestBody(body) {
        if (body === undefined || body === null) return null;
        try {
            if (typeof body === 'string') return clamp(body);
            if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
                return clamp(String(body));
            }
            if (typeof FormData !== 'undefined' && body instanceof FormData) {
                var parts = [];
                body.forEach(function(value, key) {
                    if (typeof File !== 'undefined' && value instanceof File) {
                        parts.push(encodeURIComponent(key) + '=<' + (value.name || 'file')
                            + ' ' + value.size + ' bytes ' + (value.type || '') + '>');
                    } else {
                        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
                    }
                });
                return clamp(parts.join('&'));
            }
            if (isBinary(body)) {
                return '<' + (body.byteLength !== undefined ? body.byteLength : body.length) + ' bytes of binary>';
            }
            if (typeof Blob !== 'undefined' && body instanceof Blob) {
                return '<Blob ' + body.size + ' bytes ' + (body.type || '') + '>';
            }
            return clamp(String(body));
        } catch (e) {
            return '<unreadable body: ' + e + '>';
        }
    }

    /** Neither `method` nor `url` can be read off an XHR once it is done, so we keep our own. */
    var methodKey = '__fulgurisMethod';
    var urlKey = '__fulgurisUrl';

    function headersToObject(headers) {
        var out = {};
        try {
            headers.forEach(function(value, name) {
                out[String(name).toLowerCase()] = value;
            });
        } catch (e) {
        }
        return out;
    }

    function parseRawHeaders(xhr) {
        var out = {};
        try {
            var raw = xhr.getAllResponseHeaders ? xhr.getAllResponseHeaders() : '';
            if (raw) {
                raw.trim().split(/[\r\n]+/).forEach(function(line) {
                    var at = line.indexOf(':');
                    if (at > 0) {
                        out[line.slice(0, at).trim().toLowerCase()] = line.slice(at + 1).trim();
                    }
                });
            }
        } catch (e) {
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // fetch
    // ---------------------------------------------------------------------------------------------

    var nativeFetch = window.fetch;
    if (typeof nativeFetch === 'function') {
        window.fetch = function(input, init) {
            var started = Date.now();
            var method = 'GET';
            var url = '';
            var requestBody = null;

            try {
                if (typeof Request !== 'undefined' && input instanceof Request) {
                    method = input.method;
                    url = absolute(input.url);
                } else {
                    url = absolute(String(input));
                }
                if (init && init.method) method = init.method;
                method = String(method || 'GET').toUpperCase();
                requestBody = describeRequestBody(init ? init.body : undefined);
            } catch (e) {
            }

            function describe(response) {
                var mime = '';
                try {
                    mime = response.headers.get('content-type') || '';
                } catch (e) {
                }
                return {
                    kind: 'fetch',
                    method: method,
                    url: url || (response.url || ''),
                    status: response.status,
                    statusText: response.statusText,
                    mime: mime,
                    headers: headersToObject(response.headers),
                    duration: Date.now() - started,
                    requestBody: requestBody,
                    requestBodyTruncated: wasClamped(requestBody)
                };
            }

            return nativeFetch.apply(this, arguments).then(function(response) {
                var payload = describe(response);

                // Read a copy, never the response itself: the page still gets its own body untouched.
                // Skipped for an opaque response, whose body CORS keeps from us anyway.
                if (response.type !== 'opaque' && isTextual(payload.mime)) {
                    response.clone().text().then(
                        function(text) {
                            payload.body = clamp(text);
                            payload.bodyTruncated = wasClamped(text);
                            report(payload);
                        },
                        function() {
                            report(payload);
                        }
                    );
                } else {
                    report(payload);
                }

                return response;
            }, function(error) {
                report({
                    kind: 'fetch',
                    method: method,
                    url: url,
                    duration: Date.now() - started,
                    requestBody: requestBody,
                    requestBodyTruncated: wasClamped(requestBody),
                    error: String((error && error.message) || error)
                });
                throw error;
            });
        };
    }

    // ---------------------------------------------------------------------------------------------
    // XMLHttpRequest
    // ---------------------------------------------------------------------------------------------

    var XHR = window.XMLHttpRequest;
    if (typeof XHR === 'function' && XHR.prototype) {
        var nativeOpen = XHR.prototype.open;
        var nativeSend = XHR.prototype.send;

        XHR.prototype.open = function(method, url) {
            try {
                this[methodKey] = String(method || 'GET').toUpperCase();
                this[urlKey] = absolute(String(url));
            } catch (e) {
            }
            return nativeOpen.apply(this, arguments);
        };

        XHR.prototype.send = function(body) {
            var xhr = this;
            var started = Date.now();
            var requestBody = describeRequestBody(body);

            try {
                // loadend rather than load: it also fires for a request that failed or was aborted,
                // and a failed request is exactly what the inspector is here to show.
                xhr.addEventListener('loadend', function() {
                    var payload = {
                        kind: 'xhr',
                        method: xhr[methodKey] || 'GET',
                        url: xhr[urlKey] || '',
                        status: xhr.status,
                        statusText: xhr.statusText,
                        mime: (xhr.getResponseHeader && xhr.getResponseHeader('content-type')) || '',
                        headers: parseRawHeaders(xhr),
                        duration: Date.now() - started,
                        requestBody: requestBody,
                        requestBodyTruncated: wasClamped(requestBody)
                    };

                    // responseText throws for the non-text response types, hence the type check
                    try {
                        if (xhr.responseType === '' || xhr.responseType === 'text') {
                            payload.body = clamp(xhr.responseText);
                            payload.bodyTruncated = wasClamped(xhr.responseText);
                        } else if (xhr.responseType === 'json' && xhr.response !== null) {
                            payload.body = clamp(JSON.stringify(xhr.response));
                        }
                    } catch (e) {
                    }

                    report(payload);
                });
            } catch (e) {
            }

            return nativeSend.apply(this, arguments);
        };
    }
})();
