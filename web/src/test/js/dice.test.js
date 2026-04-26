const { renderDiceResult } = require('../../main/resources/static/js/dice');
require('../../main/resources/static/js/casino-jackpot');

describe('renderDiceResult', () => {
    let resultEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        resultEl = document.getElementById('r');
    });

    test('win shows landed and predicted face plus net', () => {
        renderDiceResult(resultEl, { win: true, landed: 4, predicted: 4, net: 500 });

        expect(resultEl.classList.contains('dice-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('+500 credits');
        expect(resultEl.innerHTML).toContain('4');
    });

    test('lose distinguishes predicted vs landed', () => {
        renderDiceResult(resultEl, { win: false, landed: 2, predicted: 4, net: -100 });

        expect(resultEl.classList.contains('dice-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('100 credits');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderDiceResult(resultEl, {
            win: true, landed: 6, predicted: 6, net: 500, jackpotPayout: 2000
        });

        expect(resultEl.classList.contains('dice-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+2000 credits');
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderDiceResult(resultEl, {
            win: false, landed: 2, predicted: 4, net: -100, lossTribute: 10
        });

        expect(resultEl.innerHTML).toContain('+10 to jackpot');
    });
});
