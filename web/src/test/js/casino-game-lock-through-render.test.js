// Regression for the "auto-click can fire a second hand on top of the
// first hand's reveal" bug — particularly bad on keno (8 draws × 120ms
// stagger = ~1s of unguarded reveal between response-arrival and
// renderResult's Promise resolving).
//
// The fix in casino-game.js holds the busy lock + setDisabled(true)
// until the renderResult Promise resolves, so a script holding down
// Deal can't start the next hand until the previous reveal lands. This
// replaces the per-user, per-game CasinoCooldownService that was rolled
// back at the same time — locking the button is the friendlier UX
// since the user gets immediate visual feedback instead of a 429.

require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-game');

describe('casino-game.js — primary button stays disabled until renderResult settles', () => {
    let postJsonMock;
    let primaryBtn;
    let form;

    beforeEach(() => {
        document.body.innerHTML =
            '<form id="f">' +
            '  <input id="s" type="number" value="10">' +
            '  <button id="p" type="submit">Deal</button>' +
            '</form>' +
            '<span id="bal">100</span>' +
            '<div id="r"></div>';
        form = document.getElementById('f');
        primaryBtn = document.getElementById('p');

        postJsonMock = jest.fn();
        window.TobyApi = { postJson: postJsonMock };
        window.TobyJackpot.releasePoolBanner();
        jest.useFakeTimers();
    });

    afterEach(() => {
        jest.useRealTimers();
        delete window.TobyApi;
    });

    function bootInit(cfgOverrides) {
        return window.TobyCasinoGame.init(Object.assign({
            guildId: 'g1',
            endpoint: '/play',
            form: form,
            stakeInput: document.getElementById('s'),
            primaryBtn: primaryBtn,
            balanceEl: document.getElementById('bal'),
            resultEl: document.getElementById('r'),
            failureMessage: 'Deal failed.',
        }, cfgOverrides));
    }

    // Microtask flush — works under fake timers (`jest.advanceTimersByTime`
    // schedules timers, not microtasks). We need a few rounds because the
    // scaffold chains: postJson resolution → setTimeout → renderResult →
    // optional Promise.then → finishSettle.
    async function flushMicrotasks() {
        for (let i = 0; i < 5; i++) await Promise.resolve();
    }

    test('Promise-returning renderResult holds the disabled state until it resolves (keno / baccarat shape)', async () => {
        let resolveRender;
        const renderResultPromise = new Promise((resolve) => { resolveRender = resolve; });
        const renderResult = jest.fn(() => renderResultPromise);

        postJsonMock.mockReturnValue(Promise.resolve({ ok: true, newBalance: 100 }));
        bootInit({ renderResult: renderResult });

        form.dispatchEvent(new Event('submit'));
        // Submit: busy lock engages, button disabled.
        expect(primaryBtn.disabled).toBe(true);

        // postJson resolves → microtask → setTimeout(remaining=0) →
        // setTimeout fires → renderResult runs.
        await flushMicrotasks();
        jest.advanceTimersByTime(0);
        await flushMicrotasks();
        expect(renderResult).toHaveBeenCalledTimes(1);
        // renderResult returned a still-pending Promise → button stays
        // disabled (this is the keno fix).
        expect(primaryBtn.disabled).toBe(true);

        // Reveal animation completes → lock releases.
        resolveRender();
        await flushMicrotasks();
        expect(primaryBtn.disabled).toBe(false);
    });

    test('synchronous renderResult releases the lock once finishSettle runs (dice / slots shape)', async () => {
        postJsonMock.mockReturnValue(Promise.resolve({ ok: true, newBalance: 100 }));
        bootInit({ renderResult: jest.fn(() => undefined) });

        form.dispatchEvent(new Event('submit'));
        expect(primaryBtn.disabled).toBe(true);

        await flushMicrotasks();
        jest.advanceTimersByTime(0);
        await flushMicrotasks();
        expect(primaryBtn.disabled).toBe(false);
    });

    test('a held submit while busy fires no second postJson', async () => {
        postJsonMock.mockReturnValue(new Promise(() => {})); // never resolves
        bootInit({ renderResult: jest.fn(() => undefined) });

        form.dispatchEvent(new Event('submit'));
        form.dispatchEvent(new Event('submit'));
        form.dispatchEvent(new Event('submit'));
        await flushMicrotasks();
        expect(postJsonMock).toHaveBeenCalledTimes(1);
        expect(primaryBtn.disabled).toBe(true);
    });

    test('on a server error response the lock releases (so the user can retry)', async () => {
        postJsonMock.mockReturnValue(Promise.resolve({ ok: false, error: 'Boom' }));
        const renderResult = jest.fn();
        bootInit({ renderResult: renderResult });

        form.dispatchEvent(new Event('submit'));
        await flushMicrotasks();
        jest.advanceTimersByTime(0);
        await flushMicrotasks();
        expect(renderResult).not.toHaveBeenCalled();
        expect(primaryBtn.disabled).toBe(false);
    });
});
