const { renderCoinflipResult } = require('../../main/resources/static/js/coinflip');
require('../../main/resources/static/js/casino-jackpot');

describe('renderCoinflipResult', () => {
    let resultEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        resultEl = document.getElementById('r');
    });

    test('renders a win line with HEADS/TAILS labels and net', () => {
        renderCoinflipResult(resultEl, {
            win: true, landed: 'HEADS', predicted: 'HEADS', net: 100
        });

        expect(resultEl.classList.contains('coinflip-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('Heads');
        expect(resultEl.innerHTML).toContain('+100 credits');
    });

    test('lose path explicitly says you called X but landed Y', () => {
        renderCoinflipResult(resultEl, {
            win: false, landed: 'TAILS', predicted: 'HEADS', net: -50
        });

        expect(resultEl.classList.contains('coinflip-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('Tails');
        expect(resultEl.innerHTML).toContain('Heads');
        expect(resultEl.innerHTML).toContain('50 credits');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderCoinflipResult(resultEl, {
            win: true, landed: 'HEADS', predicted: 'HEADS', net: 100, jackpotPayout: 999
        });

        expect(resultEl.classList.contains('coinflip-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+999 credits');
    });

    test('returns early on missing result element', () => {
        expect(() => renderCoinflipResult(null, { win: true })).not.toThrow();
    });
});
