/**
 * PvP inside the Discord Activity: the page's state-read GETs and its
 * SSE stream must carry the activity session token, because the iframe
 * has no OAuth2 session cookie. GETs take a bearer header via
 * TobyApi.authHeaders; EventSource can't set headers, so the stream URL
 * carries ?activityToken= instead (ActivityTokenAuthFilter accepts
 * both). Outside the activity both must collapse to the pre-existing
 * behaviour exactly.
 */

const PVP_PATH = '../../main/resources/static/js/pvp.js';

function loadPvp() {
    let api;
    jest.isolateModules(() => {
        api = require(PVP_PATH);
    });
    return api;
}

function loadTobyApi() {
    let api;
    jest.isolateModules(() => {
        api = require('../../main/resources/static/js/api.js');
    });
    return api;
}

describe('pvp.js activity token plumbing', () => {
    beforeEach(() => {
        sessionStorage.clear();
        document.head.innerHTML = '';
        document.body.innerHTML = '';
        window.history.pushState({}, '', '/');
        delete window.TobyApi;
    });

    test('stream URL carries the activity token when present', () => {
        sessionStorage.setItem('tobyActivityToken', 'act_abc');
        loadTobyApi(); // installs window.TobyApi
        const pvp = loadPvp();

        expect(pvp.pvpStreamUrl('42')).toBe('/pvp/42/stream?activityToken=act_abc');
    });

    test('stream URL is unchanged outside the activity', () => {
        loadTobyApi();
        const pvp = loadPvp();

        expect(pvp.pvpStreamUrl('42')).toBe('/pvp/42/stream');
    });

    test('state-read options attach the bearer header inside the activity', () => {
        sessionStorage.setItem('tobyActivityToken', 'act_abc');
        loadTobyApi();
        const pvp = loadPvp();

        const opts = pvp.activityGetOpts();

        expect(opts.credentials).toBe('same-origin');
        expect(opts.headers['Authorization']).toBe('Bearer act_abc');
    });

    test('state-read options degrade gracefully without TobyApi', () => {
        const pvp = loadPvp();

        const opts = pvp.activityGetOpts();

        expect(opts.credentials).toBe('same-origin');
        expect(opts.headers).toEqual({});
    });
});
