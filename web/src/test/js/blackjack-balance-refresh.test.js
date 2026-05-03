// Regression test for "blackjack is taking the stake cost per interaction
// (e.g. hit) which is incorrect" — a UI bug where the wallet display
// (`#bj-balance`) only refreshed when a hand fully resolved. The wallet
// then appeared to "lose" the stake at exactly the moment the player
// clicked an action, even though the actual server-side debit happened
// at deal time. blackjack-solo.js now refreshes the wallet on every
// successful Deal/Continued/Resolved response, mirroring whatever
// `b.newBalance` the server echoes.
//
// We simulate the postAction flow by stubbing window.TobyApi.postJson
// and dispatching a click on the (test-built) DOM. Identical to the
// production wiring, just with the network call mocked.

describe('blackjack-solo.js — wallet refresh on every action', () => {
    let postJsonMock;

    beforeEach(() => {
        // Reset module cache so the IIFE re-runs against this test's DOM
        // and re-binds its event listeners to the freshly-built buttons.
        jest.resetModules();

        document.head.innerHTML = '';
        document.body.innerHTML = `
            <main id="main" data-guild-id="42" data-min-stake="10" data-max-stake="500" data-my-discord-id="100">
                <form id="bj-deal" autocomplete="off">
                    <input id="bj-stake" name="stake" type="number" value="100" />
                    <button type="submit">Deal</button>
                </form>
                <strong id="bj-balance">1000</strong>
                <section id="bj-table" hidden>
                    <span id="bj-dealer-total"></span>
                    <div id="bj-dealer-cards"></div>
                    <div id="bj-player-row">
                        <span id="bj-player-total"></span>
                        <div id="bj-player-cards"></div>
                        <div id="bj-player-hands" hidden></div>
                    </div>
                    <button id="bj-action-hit" type="button" disabled>Hit</button>
                    <button id="bj-action-stand" type="button" disabled>Stand</button>
                    <button id="bj-action-double" type="button" disabled>Double</button>
                    <button id="bj-action-split" type="button" disabled hidden>Split</button>
                    <div id="bj-result"></div>
                </section>
            </main>
        `;

        postJsonMock = jest.fn();
        window.TobyApi = { postJson: postJsonMock };
        // blackjack-solo.js calls window.TobyBalance.update — load the
        // shared helper into this jsdom before requiring the page IIFE
        // so the writes go through the centralized site.
        require('../../main/resources/static/js/casino-balance');
        // The IIFE schedules a refreshState() on load via fetch — stub it
        // out so we don't hit the network in jsdom.
        window.fetch = jest.fn().mockResolvedValue({
            ok: false,
            status: 404,
            json: () => Promise.resolve({}),
            headers: { get: () => null },
        });

        require('../../main/resources/static/js/blackjack-solo');
    });

    test('Deal response updates #bj-balance to the post-deal escrow', async () => {
        // Server: balance was 1000, debited stake 100 → 900.
        postJsonMock.mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 900 });

        document.getElementById('bj-deal').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();

        expect(postJsonMock).toHaveBeenCalledWith('/blackjack/42/solo/deal', { stake: 100 });
        expect(document.getElementById('bj-balance').textContent).toBe('900');
    });

    test('HIT response carries newBalance unchanged — successive HITs do not deduct stake', async () => {
        // Walk through: Deal at 100 → balance 900, then HIT, HIT, HIT.
        // None of the HITs should change the displayed balance.
        postJsonMock
            .mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 900 })  // Deal
            .mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 900 })  // HIT 1
            .mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 900 })  // HIT 2
            .mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 900 }); // HIT 3

        document.getElementById('bj-deal').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();
        expect(document.getElementById('bj-balance').textContent).toBe('900');

        const hitBtn = document.getElementById('bj-action-hit');
        // jsdom honours the `disabled` attribute on programmatic .click()s,
        // so enable the button manually instead of round-tripping through a
        // /state mock just to flip it. The handler under test runs the same
        // either way.
        hitBtn.disabled = false;
        hitBtn.click(); await flush();
        expect(document.getElementById('bj-balance').textContent).toBe('900');
        hitBtn.click(); await flush();
        expect(document.getElementById('bj-balance').textContent).toBe('900');
        hitBtn.click(); await flush();
        expect(document.getElementById('bj-balance').textContent).toBe('900');

        // 1 deal + 3 hits.
        expect(postJsonMock).toHaveBeenCalledTimes(4);
        // The HITs all hit the action endpoint, never the deal endpoint —
        // proving the UI doesn't accidentally re-deal (and re-debit) per click.
        expect(postJsonMock.mock.calls.slice(1).every(c => c[0] === '/blackjack/42/solo/action')).toBe(true);
    });

    test('SPLIT mid-hand reflects the additional pre-debit immediately', async () => {
        // Server pre-debits an extra ante on SPLIT — the client gets a
        // Continued response with the new wallet so the player sees the
        // second debit right away, not when the hand finally resolves.
        postJsonMock
            .mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 900 })  // Deal
            .mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 800 }); // SPLIT

        document.getElementById('bj-deal').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();
        expect(document.getElementById('bj-balance').textContent).toBe('900');

        const splitBtn = document.getElementById('bj-action-split');
        splitBtn.disabled = false;
        splitBtn.hidden = false;
        splitBtn.click();
        await flush();
        expect(document.getElementById('bj-balance').textContent).toBe('800');
    });

    test('Resolved response updates the wallet to the post-settlement balance', async () => {
        postJsonMock
            .mockResolvedValueOnce({ ok: true, tableId: 7, newBalance: 900 })                    // Deal
            .mockResolvedValueOnce({ ok: true, tableId: 7, resolved: true, newBalance: 1100 });  // STAND, win

        document.getElementById('bj-deal').dispatchEvent(new Event('submit', { cancelable: true }));
        await flush();
        const standBtn = document.getElementById('bj-action-stand');
        standBtn.disabled = false;
        standBtn.click();
        await flush();

        expect(document.getElementById('bj-balance').textContent).toBe('1100');
    });
});

// Drain the microtask queue so awaited promises in the tested handlers
// settle before assertions run. blackjack-solo.js chains .then() off the
// postJson promise, so a single immediate await isn't enough.
function flush() {
    return new Promise((resolve) => setTimeout(resolve, 0));
}
