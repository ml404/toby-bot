const { renderWheelResult } = require('../../main/resources/static/js/wheel');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderWheelResult', () => {
    let resultEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        resultEl = document.getElementById('r');
    });

    test('win shows landed/picked multiplier and net', () => {
        renderWheelResult(resultEl, { win: true, pick: 5, landed: 5, net: 400 });

        expect(resultEl.classList.contains('wheel-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('5×');
        expect(resultEl.innerHTML).toContain('+400 credits');
    });

    test('lose distinguishes landed vs picked', () => {
        renderWheelResult(resultEl, { win: false, pick: 5, landed: 2, net: -100 });

        expect(resultEl.classList.contains('wheel-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('2×');
        expect(resultEl.innerHTML).toContain('5×');
        expect(resultEl.innerHTML).toContain('100 credits');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderWheelResult(resultEl, {
            win: true, pick: 10, landed: 10, net: 900, jackpotPayout: 2500
        });

        expect(resultEl.classList.contains('wheel-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+2500 credits');
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderWheelResult(resultEl, {
            win: false, pick: 5, landed: 2, net: -100, lossTribute: 10
        });

        expect(resultEl.innerHTML).toContain('+10 to jackpot');
    });
});
