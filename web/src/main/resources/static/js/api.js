// Thin wrapper around fetch() for same-origin JSON POSTs that honours Spring's
// CSRF meta tags. Callers receive the parsed JSON body (or a synthetic
// { ok, error } object if the response wasn't JSON). Loaded as a prerequisite
// by any page that needs to call its own controllers from JS.
(function () {
    'use strict';

    function getCsrfToken() {
        const meta = document.querySelector('meta[name="_csrf"]');
        return meta ? meta.content : '';
    }

    function getCsrfHeader() {
        const meta = document.querySelector('meta[name="_csrf_header"]');
        return meta ? meta.content : 'X-CSRF-TOKEN';
    }

    // Discord Activity support: inside the Activity iframe there is no
    // OAuth2 session cookie — the bootstrap shell (activity.js) issues a
    // session token instead and threads it into the first page via
    // ?activityToken=. Stash it in sessionStorage and attach it as a
    // bearer header on every JSON call so the existing game endpoints
    // authenticate through ActivityTokenAuthFilter. Outside the iframe
    // the param/storage are absent and all of this no-ops.
    function activityToken() {
        try {
            const fromUrl = new URLSearchParams(window.location.search).get('activityToken');
            if (fromUrl) {
                try { sessionStorage.setItem('tobyActivityToken', fromUrl); } catch (e) { /* sandboxed */ }
                return fromUrl;
            }
            return sessionStorage.getItem('tobyActivityToken') || '';
        } catch (e) {
            return '';
        }
    }

    function withActivityAuth(headers) {
        const token = activityToken();
        if (token) headers['Authorization'] = 'Bearer ' + token;
        return headers;
    }

    // For pages that issue their own fetch() calls (blackjack's state
    // polling, the lobby refresh) rather than going through postJson:
    // merges the activity bearer header into the caller's headers when
    // running inside the Discord Activity, and is a no-op otherwise.
    function authHeaders(extra) {
        return withActivityAuth(extra || {});
    }

    function postJson(url, body) {
        const headers = withActivityAuth({ 'Content-Type': 'application/json', 'Accept': 'application/json' });
        const token = getCsrfToken();
        if (token) headers[getCsrfHeader()] = token;
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers,
            body: JSON.stringify(body)
        }).then(function (r) {
            // Centralised jackpot-pool banner refresh: every casino response
            // carries the post-action pool size in X-Jackpot-Pool. Pages
            // without the banner (duel/trade/tip) silently no-op inside
            // updatePoolBanner.
            const poolHeader = r.headers.get('X-Jackpot-Pool');
            if (poolHeader != null && window.TobyJackpot) {
                const pool = Number(poolHeader);
                if (!Number.isNaN(pool)) {
                    window.TobyJackpot.updatePoolBanner({ jackpotPool: pool });
                }
            }
            return r.json().catch(function () {
                return { ok: r.ok, error: r.ok ? null : 'Request failed.' };
            });
        });
    }

    function del(url) {
        const headers = withActivityAuth({ 'Accept': 'application/json' });
        const token = getCsrfToken();
        if (token) headers[getCsrfHeader()] = token;
        return fetch(url, {
            method: 'DELETE',
            credentials: 'same-origin',
            headers: headers
        }).then(function (r) {
            return r.json().catch(function () {
                return { ok: r.ok, error: r.ok ? null : 'Request failed.' };
            });
        });
    }

    // Full-page navigations can't carry a bearer header, so when an
    // activity token is live, append it to same-origin links as they're
    // clicked (capture phase, before the browser follows the href). This
    // keeps "← All servers" / navbar navigation working inside the
    // Activity iframe without threading the token through every template.
    function rewriteActivityLinks() {
        document.addEventListener('click', function (e) {
            // Resolved per click, not at load: outside the activity this is
            // a cheap no-op, and a token that only lands after page load
            // (sessionStorage write from another script) still counts.
            const token = activityToken();
            if (!token) return;
            const target = e.target;
            const anchor = (target && target.closest) ? target.closest('a[href]') : null;
            if (!anchor) return;
            let url;
            try {
                url = new URL(anchor.getAttribute('href'), window.location.href);
            } catch (err) {
                return;
            }
            if (url.origin !== window.location.origin) return;
            if (url.searchParams.get('activityToken')) return;
            url.searchParams.set('activityToken', token);
            anchor.setAttribute('href', url.pathname + url.search + url.hash);
        }, true);
    }
    rewriteActivityLinks();

    window.TobyApi = { postJson: postJson, del: del, activityToken: activityToken, authHeaders: authHeaders };

    if (typeof module !== 'undefined') {
        module.exports = { postJson: postJson, del: del, activityToken: activityToken, authHeaders: authHeaders };
    }
})();
