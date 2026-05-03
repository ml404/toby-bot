// Drives the scratch.js IIFE end-to-end to confirm the central
// jackpot-pool banner stays held across the user-driven reveal — and
// is only released when the player has uncovered every cell (or the
// server returned a non-ok body). Lives in a separate file from
// `scratch.test.js` because it needs to reload the IIFE against a
// freshly-built scratch DOM (jest caches the require otherwise and
// the IIFE bails on its `els` guard the first time round).

function buildScratchDom() {
    const cells = [];
    for (let i = 0; i < 9; i++) {
        cells.push(
            '<button class="scratch-cell" data-index="' + i + '">' +
            '<span class="scratch-cell-cover">?</span>' +
            '<span class="scratch-cell-face"></span>' +
            '</button>'
        );
    }
    document.body.innerHTML =
        '<main data-guild-id="g1" data-toby-coins="0" data-market-price="0" data-match-threshold="5">' +
        '<div class="casino-jackpot-banner"><strong>0</strong></div>' +
        '<form id="scratch-bet">' +
        '<input id="scratch-stake" type="number" value="10">' +
        '<button id="scratch-buy" type="submit">Buy</button>' +
        '</form>' +
        '<span id="scratch-balance">100</span>' +
        '<div id="scratch-result"></div>' +
        '<section class="scratch-table"></section>' +
        '<div id="scratch-cells">' + cells.join('') + '</div>' +
        '<button id="scratch-reveal-all">Reveal all</button>' +
        '</main>';
}

function loadScratchIIFE() {
    jest.isolateModules(() => {
        require('../../main/resources/static/js/casino-jackpot');
        require('../../main/resources/static/js/casino-minigame-dom');
        require('../../main/resources/static/js/casino-result');
        require('../../main/resources/static/js/casino-render');
        require('../../main/resources/static/js/casino-game');
        require('../../main/resources/static/js/scratch');
    });
}

function makeCard(jackpotPool) {
    return {
        ok: true,
        cells: ['⭐', '🍒', '🍋', '⭐', '🔔', '🍒', '⭐', '🍋', '🍒'],
        winningSymbol: '⭐',
        matchCount: 3,
        net: -10,
        newBalance: 90,
        jackpotPool: jackpotPool,
    };
}

describe('scratch.js — pool banner lock around reveal', () => {
    let postJsonMock;
    let holdSpy;
    let releaseSpy;

    beforeEach(() => {
        buildScratchDom();
        postJsonMock = jest.fn();
        window.TobyApi = { postJson: postJsonMock };
        jest.useFakeTimers();
        loadScratchIIFE();
        // Spy AFTER the IIFE re-installs window.TobyJackpot.
        holdSpy = jest.spyOn(window.TobyJackpot, 'holdPoolBanner');
        releaseSpy = jest.spyOn(window.TobyJackpot, 'releasePoolBanner');
    });

    afterEach(() => {
        jest.useRealTimers();
        holdSpy.mockRestore();
        releaseSpy.mockRestore();
        delete window.TobyApi;
    });

    function submitBuy() {
        const form = document.getElementById('scratch-bet');
        form.dispatchEvent(new Event('submit', { cancelable: true }));
    }

    function clickCell(idx) {
        document
            .querySelector('.scratch-cell[data-index="' + idx + '"]')
            .click();
    }

    test('holdPoolBanner fires on buy submit, before any reveal', async () => {
        postJsonMock.mockResolvedValue(makeCard(500));
        submitBuy();
        await Promise.resolve();
        await Promise.resolve();
        // Flush the casino-game.js setTimeout(0) → calls stopAnimation
        // which stashes the card and shows the Reveal-all button.
        jest.advanceTimersByTime(0);

        expect(holdSpy).toHaveBeenCalledTimes(1);
        expect(releaseSpy).not.toHaveBeenCalled();
    });

    test('release fires only when the LAST cell is revealed', async () => {
        postJsonMock.mockResolvedValue(makeCard(500));
        submitBuy();
        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(0);

        for (let i = 0; i < 8; i++) {
            clickCell(i);
            expect(releaseSpy).not.toHaveBeenCalled();
        }
        clickCell(8);
        expect(releaseSpy).toHaveBeenCalledTimes(1);
    });

    test('Reveal all triggers exactly one release after the cascade', async () => {
        postJsonMock.mockResolvedValue(makeCard(500));
        submitBuy();
        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(0);

        document.getElementById('scratch-reveal-all').click();
        // Cascade staggers cells at 60ms intervals — 9 cells * 60ms.
        jest.advanceTimersByTime(9 * 60);

        expect(releaseSpy).toHaveBeenCalledTimes(1);
    });

    test('server error path releases the lock from stopAnimation', async () => {
        postJsonMock.mockResolvedValue({ ok: false, error: 'broke' });
        submitBuy();
        await Promise.resolve();
        await Promise.resolve();
        jest.advanceTimersByTime(0);

        // Hold acquired (startAnimation runs unconditionally), and the
        // failure branch of stopAnimation must release it so the banner
        // doesn't get stranded.
        expect(holdSpy).toHaveBeenCalledTimes(1);
        expect(releaseSpy).toHaveBeenCalledTimes(1);
    });

    test('cell clicks before the response arrives do not release the lock', () => {
        // postJson never resolves — activeCard stays null, so revealCell
        // bails before reaching the all-revealed branch. The lock is
        // still held by startAnimation.
        postJsonMock.mockImplementation(() => new Promise(() => {}));
        submitBuy();

        for (let i = 0; i < 9; i++) {
            clickCell(i);
        }

        expect(holdSpy).toHaveBeenCalledTimes(1);
        expect(releaseSpy).not.toHaveBeenCalled();
    });
});
