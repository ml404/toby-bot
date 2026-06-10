/*
 * Opens an SSE stream to /api/notifications/stream while the user is
 * authenticated and pops toasts for each engagement event the backend
 * pushes. Bails silently on anonymous pages (no <meta name="user-authenticated">)
 * so the script can ship in fragments/head.html unconditionally without
 * spamming 401s on the login page.
 *
 * Reconnect: exponential backoff 1s → 30s on transient errors. Auth
 * failures (the EventSource never reaches readyState=OPEN on a fresh
 * session) trip the same backoff but cap quickly — the server side
 * answers with 401 immediately, so retries are cheap and the loop
 * naturally settles when the user logs back in (next page load).
 */
(function () {
    var authMeta = document.querySelector('meta[name="user-authenticated"]');
    if (!authMeta || authMeta.getAttribute('content') !== 'true') return;

    var STREAM_URL = '/api/notifications/stream';
    var EVENT_NAMES = ['achievement', 'levelUp', 'tip', 'lotteryDrawn'];
    var INITIAL_BACKOFF_MS = 1000;
    var MAX_BACKOFF_MS = 30000;

    var backoff = INITIAL_BACKOFF_MS;
    var reconnectTimer = null;
    var source = null;

    function showToast(payload) {
        if (!window.TobyToasts || typeof window.TobyToasts.show !== 'function') return;
        var title = payload.title || '';
        var body = payload.body || '';
        var message = title ? (body ? title + ' — ' + body : title) : body;
        var opts = { type: payload.type || 'info', duration: 6000 };
        if (payload.deepLink) {
            opts.action = {
                label: 'View',
                onClick: function () { window.location.href = payload.deepLink; },
            };
        }
        window.TobyToasts.show(message, opts);
    }

    function handleEvent(evt) {
        var payload;
        try { payload = JSON.parse(evt.data); }
        catch (_e) { return; }
        if (!payload || typeof payload !== 'object') return;
        showToast(payload);
    }

    function scheduleReconnect() {
        if (reconnectTimer) return;
        reconnectTimer = setTimeout(function () {
            reconnectTimer = null;
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            connect();
        }, backoff);
    }

    function streamUrl() {
        // Inside the Discord Activity iframe there's no session cookie and
        // EventSource can't carry an Authorization header — thread the
        // activity session token through as a query param instead
        // (ActivityTokenAuthFilter accepts it). No-ops on the normal web.
        var token = (window.TobyApi && typeof window.TobyApi.activityToken === 'function')
            ? window.TobyApi.activityToken()
            : '';
        return token
            ? STREAM_URL + '?activityToken=' + encodeURIComponent(token)
            : STREAM_URL;
    }

    function connect() {
        try { if (source) source.close(); } catch (_e) {}
        source = new EventSource(streamUrl());

        source.addEventListener('open', function () {
            backoff = INITIAL_BACKOFF_MS;
        });

        EVENT_NAMES.forEach(function (name) {
            source.addEventListener(name, handleEvent);
        });

        source.addEventListener('error', function () {
            try { source.close(); } catch (_e) {}
            scheduleReconnect();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', connect);
    } else {
        connect();
    }
})();
