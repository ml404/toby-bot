/**
 * Discord Activity auth plumbing in api.js: when the bootstrap shell
 * (activity.js) threads a session token into a page via ?activityToken=,
 * api.js must (1) stash it in sessionStorage, (2) attach it as a bearer
 * header on every JSON call, and (3) append it to same-origin links so
 * full-page navigation inside the Activity iframe stays authenticated.
 * Outside the iframe none of that may fire — regular dashboard requests
 * must look exactly as before.
 */

const API_PATH = '../../main/resources/static/js/api.js';

function mockFetch() {
    const calls = [];
    global.fetch = jest.fn(function (url, opts) {
        calls.push({ url: url, opts: opts });
        return Promise.resolve({
            ok: true,
            headers: { get: () => null },
            json: () => Promise.resolve({ ok: true }),
        });
    });
    return calls;
}

function loadApi() {
    let api;
    jest.isolateModules(() => {
        api = require(API_PATH);
    });
    return api;
}

describe('api.js activity token plumbing', () => {
    beforeEach(() => {
        sessionStorage.clear();
        document.head.innerHTML = '';
        document.body.innerHTML = '';
        window.history.pushState({}, '', '/');
    });

    test('token in the URL is stashed and attached as a bearer header', async () => {
        window.history.pushState({}, '', '/casino/1/slots?activityToken=act_abc');
        const calls = mockFetch();
        const api = loadApi();

        await api.postJson('/casino/1/slots/spin', { stake: 10 });

        expect(calls[0].opts.headers['Authorization']).toBe('Bearer act_abc');
        expect(sessionStorage.getItem('tobyActivityToken')).toBe('act_abc');
    });

    test('token survives navigation via sessionStorage when absent from the URL', async () => {
        sessionStorage.setItem('tobyActivityToken', 'act_abc');
        const calls = mockFetch();
        const api = loadApi();

        await api.del('/something');

        expect(calls[0].opts.headers['Authorization']).toBe('Bearer act_abc');
    });

    test('without a token no Authorization header is sent', async () => {
        const calls = mockFetch();
        const api = loadApi();

        await api.postJson('/casino/1/slots/spin', { stake: 10 });

        expect(calls[0].opts.headers['Authorization']).toBeUndefined();
    });

    test('clicking a same-origin link appends the token to its href', () => {
        window.history.pushState({}, '', '/casino/1/slots?activityToken=act_abc');
        loadApi();

        const anchor = document.createElement('a');
        anchor.setAttribute('href', '/casino/guilds?pick=true');
        document.body.appendChild(anchor);
        // Swallow jsdom's unimplemented navigation after the capture-phase
        // rewriter has run.
        document.addEventListener('click', (e) => e.preventDefault());
        anchor.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));

        expect(anchor.getAttribute('href')).toBe('/casino/guilds?pick=true&activityToken=act_abc');
    });

    test('external links are never rewritten', () => {
        window.history.pushState({}, '', '/?activityToken=act_abc');
        loadApi();

        const anchor = document.createElement('a');
        anchor.setAttribute('href', 'https://discord.com/invite/whatever');
        document.body.appendChild(anchor);
        document.addEventListener('click', (e) => e.preventDefault());
        anchor.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));

        expect(anchor.getAttribute('href')).toBe('https://discord.com/invite/whatever');
    });

    test('authHeaders merges the bearer token into caller-supplied headers', () => {
        sessionStorage.setItem('tobyActivityToken', 'act_abc');
        const api = loadApi();

        const headers = api.authHeaders({ 'Accept': 'text/html' });

        expect(headers['Authorization']).toBe('Bearer act_abc');
        expect(headers['Accept']).toBe('text/html');
    });

    test('authHeaders is a no-op outside the activity', () => {
        const api = loadApi();

        expect(api.authHeaders({})).toEqual({});
        expect(api.authHeaders()).toEqual({});
    });

    test('outside the activity no click listener rewrites anything', () => {
        loadApi();

        const anchor = document.createElement('a');
        anchor.setAttribute('href', '/casino/guilds');
        document.body.appendChild(anchor);
        document.addEventListener('click', (e) => e.preventDefault());
        anchor.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));

        expect(anchor.getAttribute('href')).toBe('/casino/guilds');
    });
});
