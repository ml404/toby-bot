/**
 * Inside the Discord Activity the cross-guild pickers ("← All servers",
 * /{section}/guilds) can't render: they need an OAuth2 authorized client
 * that activity sessions don't register, so following one bounces a fresh
 * activity user into the external OAuth redirect and the sandbox kills
 * the iframe. api.js therefore retargets picker-bound links to the
 * activity's own games selector (/activity/casino/{guildId}) — the
 * launch guild is stashed when the selector page (always the first page
 * in the nested frame) loads. Outside the activity nothing may change.
 */

const API_PATH = '../../main/resources/static/js/api.js';

function loadApi() {
    let api;
    jest.isolateModules(() => {
        api = require(API_PATH);
    });
    return api;
}

function clickThrough(anchor) {
    document.addEventListener('click', (e) => e.preventDefault());
    anchor.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
}

describe('api.js activity back-link retargeting', () => {
    beforeEach(() => {
        sessionStorage.clear();
        document.head.innerHTML = '';
        document.body.innerHTML = '';
        window.history.pushState({}, '', '/');
    });

    test('the selector page stashes the launch guild id', () => {
        window.history.pushState({}, '', '/activity/casino/42?activityToken=act_abc');

        loadApi();

        expect(sessionStorage.getItem('tobyActivityGuildId')).toBe('42');
    });

    test('clicked picker links retarget to the games selector with the token', () => {
        window.history.pushState({}, '', '/casino/42/slots?activityToken=act_abc');
        sessionStorage.setItem('tobyActivityGuildId', '42');
        loadApi();

        const anchor = document.createElement('a');
        anchor.setAttribute('href', '/casino/guilds?pick=true');
        document.body.appendChild(anchor);
        clickThrough(anchor);

        expect(anchor.getAttribute('href')).toBe('/activity/casino/42?activityToken=act_abc');
    });

    test('non-picker links only get the token appended', () => {
        window.history.pushState({}, '', '/casino/42/slots?activityToken=act_abc');
        sessionStorage.setItem('tobyActivityGuildId', '42');
        loadApi();

        const anchor = document.createElement('a');
        anchor.setAttribute('href', '/blackjack/42');
        document.body.appendChild(anchor);
        clickThrough(anchor);

        expect(anchor.getAttribute('href')).toBe('/blackjack/42?activityToken=act_abc');
    });

    test('without a stashed guild id picker links keep their target (token only)', () => {
        window.history.pushState({}, '', '/casino/42/slots?activityToken=act_abc');
        loadApi();

        const anchor = document.createElement('a');
        anchor.setAttribute('href', '/casino/guilds?pick=true');
        document.body.appendChild(anchor);
        clickThrough(anchor);

        expect(anchor.getAttribute('href')).toBe('/casino/guilds?pick=true&activityToken=act_abc');
    });

    test('visible back-links are retargeted and relabelled at load', () => {
        window.history.pushState({}, '', '/casino/42/slots?activityToken=act_abc');
        sessionStorage.setItem('tobyActivityGuildId', '42');
        document.body.innerHTML =
            '<a class="back-link" href="/casino/guilds?pick=true">&larr; All servers</a>' +
            '<a class="back-link" href="/leaderboards">&larr; All leaderboards</a>';

        loadApi();

        const [picker, leaderboards] = document.querySelectorAll('a.back-link');
        expect(picker.getAttribute('href')).toBe('/activity/casino/42');
        expect(picker.textContent).toBe('← All games');
        // Non-picker back-links (leaderboard) are not a cross-guild detour
        // and stay as authored.
        expect(leaderboards.getAttribute('href')).toBe('/leaderboards');
        expect(leaderboards.textContent).toBe('← All leaderboards');
    });

    test('outside the activity back-links and picker clicks are untouched', () => {
        document.body.innerHTML =
            '<a class="back-link" href="/casino/guilds?pick=true">&larr; All servers</a>';

        loadApi();

        const anchor = document.querySelector('a.back-link');
        clickThrough(anchor);

        expect(anchor.getAttribute('href')).toBe('/casino/guilds?pick=true');
        expect(anchor.textContent).toBe('← All servers');
    });
});
