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

    function postJson(url, body) {
        const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
        const token = getCsrfToken();
        if (token) headers[getCsrfHeader()] = token;
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers,
            body: JSON.stringify(body)
        }).then(function (r) {
            return r.json().catch(function () {
                return { ok: r.ok, error: r.ok ? null : 'Request failed.' };
            });
        });
    }

    window.TobyApi = { postJson: postJson };

    if (typeof module !== 'undefined') {
        module.exports = { postJson: postJson };
    }
})();
