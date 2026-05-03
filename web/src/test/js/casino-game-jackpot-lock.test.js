// Drives `window.TobyCasinoGame.init` end-to-end with a stubbed postJson
// to confirm that the shared scaffold acquires the central jackpot-pool
// lock before the request and releases it on the settle tick (or on a
// network / server error). This is what stops the per-guild "Jackpot:"
// banner from leaking the outcome before the reveal animation lands.

require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-game');

describe('casino-game.js — pool banner lock around settle', () => {
    let postJsonMock;
    let holdSpy;
    let releaseSpy;

    beforeEach(() => {
        document.body.innerHTML =
            '<form id="f">' +
            '  <input id="s" type="number" value="10">' +
            '  <button id="p" type="submit">Spin</button>' +
            '</form>' +
            '<span id="bal">100</span>' +
            '<div id="r"></div>' +
            '<div class="casino-jackpot-banner"><strong>0</strong></div>';

        postJsonMock = jest.fn();
        window.TobyApi = { postJson: postJsonMock };
        // Reset any leaked hold from a previous test before installing
        // spies, so the first call we observe is the one this test makes.
        window.TobyJackpot.releasePoolBanner();
        holdSpy = jest.spyOn(window.TobyJackpot, 'holdPoolBanner');
        releaseSpy = jest.spyOn(window.TobyJackpot, 'releasePoolBanner');
        jest.useFakeTimers();
    });

    afterEach(() => {
        jest.useRealTimers();
        holdSpy.mockRestore();
        releaseSpy.mockRestore();
        delete window.TobyApi;
    });

    function buildGame(overrides) {
        const cfg = Object.assign({
            form: document.getElementById('f'),
            stakeInput: document.getElementById('s'),
            primaryBtn: document.getElementById('p'),
            balanceEl: document.getElementById('bal'),
            resultEl: document.getElementById('r'),
            guildId: 'g1',
            endpoint: '/spin',
            minSettleMs: 800,
        }, overrides || {});
        return window.TobyCasinoGame.init(cfg);
    }

    test('hold acquired before postJson resolves', () => {
        postJsonMock.mockImplementation(() => new Promise(() => {})); // never resolves
        const game = buildGame();
        game.run(false);
        expect(holdSpy).toHaveBeenCalledTimes(1);
        expect(releaseSpy).not.toHaveBeenCalled();
    });

    test('release fires only after minSettleMs has elapsed', async () => {
        postJsonMock.mockResolvedValue({ ok: true, jackpotPool: 500, newBalance: 90 });
        const game = buildGame({ minSettleMs: 800 });
        game.run(false);

        // Flush the .then microtask so the setTimeout is scheduled.
        await Promise.resolve();
        await Promise.resolve();

        expect(releaseSpy).not.toHaveBeenCalled();
        jest.advanceTimersByTime(799);
        expect(releaseSpy).not.toHaveBeenCalled();
        jest.advanceTimersByTime(1);
        expect(releaseSpy).toHaveBeenCalledTimes(1);
    });

    test('release fires on network error (postJson rejects)', async () => {
        postJsonMock.mockRejectedValue(new Error('network down'));
        const game = buildGame();
        game.run(false);

        // Flush the .catch microtask.
        await Promise.resolve();
        await Promise.resolve();

        expect(releaseSpy).toHaveBeenCalledTimes(1);
    });

    test('release fires on a server-side error body (ok: false)', async () => {
        postJsonMock.mockResolvedValue({ ok: false, error: 'broke' });
        const game = buildGame({ minSettleMs: 200 });
        game.run(false);

        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(200);

        expect(releaseSpy).toHaveBeenCalledTimes(1);
    });

    test('autoApplyBalance: false skips the casino-game.js hold', () => {
        // Scratch sets autoApplyBalance: false because its reveal beat
        // is user-driven; it manages the lock from inside scratch.js.
        // The shared scaffold must not also try to hold/release.
        postJsonMock.mockImplementation(() => new Promise(() => {}));
        const game = buildGame({ autoApplyBalance: false });
        game.run(false);

        expect(holdSpy).not.toHaveBeenCalled();
        expect(releaseSpy).not.toHaveBeenCalled();
    });

    test('autoApplyBalance: false skips the release on settle too', async () => {
        postJsonMock.mockResolvedValue({ ok: true, jackpotPool: 12 });
        const game = buildGame({ autoApplyBalance: false, minSettleMs: 50 });
        game.run(false);

        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(50);

        expect(releaseSpy).not.toHaveBeenCalled();
    });

    test('async renderResult holds the lock until its Promise resolves', async () => {
        // Mirrors keno / baccarat: renderResult kicks off a staggered
        // reveal that lands later via setTimeouts. The scaffold must
        // wait for the Promise before applying balance and releasing
        // the banner, otherwise the banner ticks before the cells /
        // cards finish landing.
        postJsonMock.mockResolvedValue({ ok: true, jackpotPool: 999, newBalance: 50 });
        let resolveReveal;
        const renderResult = jest.fn(function () {
            return new Promise(function (r) { resolveReveal = r; });
        });
        const game = buildGame({ minSettleMs: 0, renderResult: renderResult });
        game.run(false);

        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(0);

        // settle setTimeout has fired and renderResult has been invoked,
        // but its Promise is still pending — lock must stay held.
        expect(renderResult).toHaveBeenCalledTimes(1);
        expect(releaseSpy).not.toHaveBeenCalled();

        // Balance must NOT have been written either — the user would
        // see it tick before the reveal lands.
        expect(document.getElementById('bal').textContent).toBe('100');

        // Resolve the reveal — now the scaffold should flush both.
        resolveReveal();
        await Promise.resolve();
        await Promise.resolve();

        expect(releaseSpy).toHaveBeenCalledTimes(1);
        expect(document.getElementById('bal').textContent).toBe('50');
    });

    test('async renderResult releases the lock even on rejection', async () => {
        // Defensive: if the per-game reveal animation throws (e.g. a
        // rendering bug), the banner must still recover.
        postJsonMock.mockResolvedValue({ ok: true, jackpotPool: 7, newBalance: 90 });
        let rejectReveal;
        const renderResult = jest.fn(function () {
            return new Promise(function (_, rej) { rejectReveal = rej; });
        });
        const game = buildGame({ minSettleMs: 0, renderResult: renderResult });
        game.run(false);

        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(0);
        expect(releaseSpy).not.toHaveBeenCalled();

        rejectReveal(new Error('boom'));
        await Promise.resolve();
        await Promise.resolve();

        expect(releaseSpy).toHaveBeenCalledTimes(1);
    });

    test('synchronous renderResult (returns undefined) releases immediately', async () => {
        postJsonMock.mockResolvedValue({ ok: true, jackpotPool: 1, newBalance: 50 });
        const renderResult = jest.fn(function () { /* sync, no return */ });
        const game = buildGame({ minSettleMs: 0, renderResult: renderResult });
        game.run(false);

        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(0);

        expect(renderResult).toHaveBeenCalledTimes(1);
        expect(releaseSpy).toHaveBeenCalledTimes(1);
        expect(document.getElementById('bal').textContent).toBe('50');
    });
});
