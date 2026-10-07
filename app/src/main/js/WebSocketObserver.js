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
 * All portions of the code written by Stéphane Lenclud are Copyright © 2020 Stéphane Lenclud.
 * All Rights Reserved.
 */

/**
 * Reports WebSocket lifecycle to our network inspector.
 *
 * WebView never routes a `ws://`/`wss://` upgrade through `shouldInterceptRequest`, so no network
 * engine can see a WebSocket: the only vantage point is the page itself. This wraps the page's
 * `WebSocket` constructor and reports every connection as it is created, opened, closed or fails,
 * through the console channel our inspector already listens on (`fulguris: ` messages, see
 * WebPageChromeClient.onConsoleMessage and ThemeColor.js).
 *
 * What this can and cannot see: it reports what the page's own WebSocket object exposes - the URL,
 * the requested and negotiated subprotocols, the lifecycle and the close code/reason. It cannot see
 * the HTTP 101 handshake response headers, as those never surface to page JavaScript.
 *
 * Message payload: `fulguris: ws: <json>` with `id` (stable across a socket's events), `url`,
 * `state` (connecting/open/closed/error), `requested`, `protocol`, `code`, `reason`, `wasClean`.
 *
 * Must be injected before any page script runs a WebSocket, hence from onPageStarted.
 */
(function() {
    'use strict';

    if (window._fulgurisWsInstalled) return;
    window._fulgurisWsInstalled = true;

    var Native = window.WebSocket;
    if (typeof Native !== 'function') return;

    var nextId = 0;

    /** Report one event of one socket through the console channel our inspector reads. */
    function report(payload) {
        try {
            console.debug('fulguris: ws: ' + JSON.stringify(payload));
        } catch (e) {
            // Reporting is a debugging aid, it must never break the page
        }
    }

    /** The URL and requested protocols as a socket was constructed, whatever it becomes later. */
    function requestedProtocols(protocols) {
        if (typeof protocols === 'string') return [protocols];
        if (protocols && protocols.length !== undefined) return Array.prototype.slice.call(protocols);
        return [];
    }

    function Wrapped(target, args) {
        var ws = Reflect.construct(target, args);
        var id = ++nextId;
        // `ws.url` is what we constructed, resolved against the document: a page may well open
        // `new WebSocket('/feed')`, and a bare path in the inspector names no host at all
        var url = (args[0] === undefined || args[0] === null) ? '' : (ws.url || String(args[0]));
        var requested = requestedProtocols(args[1]);

        // Report the attempt, not just its outcome: a socket that never opens - the case our
        // inspector is most needed for - would otherwise leave no trace at all.
        report({ id: id, url: url, requested: requested, state: 'connecting' });

        ws.addEventListener('open', function() {
            report({ id: id, url: url, requested: requested, state: 'open', protocol: ws.protocol });
        });
        ws.addEventListener('error', function() {
            report({ id: id, url: url, requested: requested, state: 'error' });
        });
        ws.addEventListener('close', function(event) {
            report({
                id: id,
                url: url,
                requested: requested,
                state: 'closed',
                protocol: ws.protocol,
                code: event.code,
                reason: event.reason,
                wasClean: event.wasClean
            });
        });

        return ws;
    }

    // A Proxy rather than a subclass: `new WebSocket(...)` must still hand back a genuine WebSocket,
    // so that `instanceof`, `WebSocket.OPEN` and `WebSocket.prototype` keep working for pages that
    // rely on them. Only construction is intercepted; every other lookup falls through untouched.
    window.WebSocket = new Proxy(Native, {
        construct: function(target, args) {
            return Wrapped(target, args);
        }
    });
})();
