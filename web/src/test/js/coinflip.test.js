const { renderCoinflipResult } = require('../../main/resources/static/js/coinflip');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderCoinflipResult', () => {
    let resultEl;
    let tableEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div><section id="t"></section>';
        resultEl = document.getElementById('r');
        tableEl = document.getElementById('t');
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

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderCoinflipResult(resultEl, {
            win: false, landed: 'TAILS', predicted: 'HEADS', net: -50, lossTribute: 5
        });

        expect(resultEl.innerHTML).toContain('+5 to jackpot');
    });

    test('win flashes a chip stack on the table; loss leaves it untouched', () => {
        renderCoinflipResult(resultEl, {
            win: true, landed: 'HEADS', predicted: 'HEADS', net: 100,
        }, tableEl);
        const stack = tableEl.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        expect(stack.querySelector('.casino-chip-payout').textContent).toBe('+100');

        tableEl.querySelectorAll('.casino-chip-stack').forEach((el) => el.remove());
        renderCoinflipResult(resultEl, {
            win: false, landed: 'TAILS', predicted: 'HEADS', net: -50,
        }, tableEl);
        expect(tableEl.querySelector('.casino-chip-stack')).toBeNull();
    });
});
