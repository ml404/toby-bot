// Order matters: load `casino-balance` *before* the scratch module so
// `window.TobyBalance.update` is registered by the time the scratch
// module's top-level `renderScratchResult` ends up calling it.
require('../../main/resources/static/js/casino-balance');
const { renderScratchResult } = require('../../main/resources/static/js/scratch');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderScratchResult', () => {
    let resultEl;
    let balanceEl;
    let tableEl;

    beforeEach(() => {
        document.body.innerHTML =
            '<div id="r"></div><span id="b">0</span><section id="t"></section>';
        resultEl = document.getElementById('r');
        balanceEl = document.getElementById('b');
        tableEl = document.getElementById('t');
    });

    test('win shows match count, winning symbol, and net', () => {
        renderScratchResult(resultEl, {
            win: true, matchCount: 5, winningSymbol: '⭐', net: 200, newBalance: 1_200
        }, 5, balanceEl);

        expect(resultEl.classList.contains('scratch-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('5×');
        expect(resultEl.innerHTML).toContain('⭐');
        expect(resultEl.innerHTML).toContain('+200 credits');
        expect(balanceEl.textContent).toBe('1200');
    });

    test('lose path uses the matchThreshold parameter in the message', () => {
        renderScratchResult(resultEl, {
            win: false, net: -50, newBalance: 950
        }, 5, balanceEl);

        expect(resultEl.classList.contains('scratch-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('No 5-of-a-kind');
        expect(resultEl.innerHTML).toContain('50 credits');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderScratchResult(resultEl, {
            win: true, matchCount: 9, winningSymbol: '⭐', net: 18000, newBalance: 25_000,
            jackpotPayout: 7000
        }, 5, balanceEl);

        expect(resultEl.classList.contains('scratch-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+7000 credits');
    });

    test('skips balance update when newBalance is missing', () => {
        balanceEl.textContent = '500';
        renderScratchResult(resultEl, { win: false, net: -10 }, 3, balanceEl);

        expect(balanceEl.textContent).toBe('500');
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderScratchResult(resultEl, {
            win: false, net: -100, newBalance: 900, lossTribute: 10
        }, 5, balanceEl);

        expect(resultEl.innerHTML).toContain('+10 to jackpot');
    });

    // Chip flourish has moved to casino-win-settle (the shared helper);
    // covered in casino-win-settle.test.js. renderScratchResult now
    // only owns the result line + class toggling + balance update.
});
