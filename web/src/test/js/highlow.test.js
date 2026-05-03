const {
    renderHighlowResult,
    highlowCardLabel,
    highlowFormatMultiplier,
} = require('../../main/resources/static/js/highlow');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('highlowCardLabel', () => {
    test('maps face cards to letters', () => {
        expect(highlowCardLabel(1)).toBe('A');
        expect(highlowCardLabel(11)).toBe('J');
        expect(highlowCardLabel(12)).toBe('Q');
        expect(highlowCardLabel(13)).toBe('K');
    });

    test('returns the number string for spot cards', () => {
        expect(highlowCardLabel(2)).toBe('2');
        expect(highlowCardLabel(7)).toBe('7');
        expect(highlowCardLabel(10)).toBe('10');
    });
});

describe('highlowFormatMultiplier', () => {
    test('formats positive multipliers with two decimals and a x suffix', () => {
        expect(highlowFormatMultiplier(1.5)).toBe('1.50×');
        expect(highlowFormatMultiplier(12)).toBe('12.00×');
        expect(highlowFormatMultiplier('2.4')).toBe('2.40×');
    });

    test('returns empty string for missing or non-positive values', () => {
        expect(highlowFormatMultiplier(undefined)).toBe('');
        expect(highlowFormatMultiplier(null)).toBe('');
        expect(highlowFormatMultiplier(0)).toBe('');
        expect(highlowFormatMultiplier(NaN)).toBe('');
    });
});

describe('renderHighlowResult', () => {
    let resultEl;
    let tableEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div><section id="t"></section>';
        resultEl = document.getElementById('r');
        tableEl = document.getElementById('t');
    });

    test('win on HIGHER renders next > anchor and shows the realised multiplier', () => {
        renderHighlowResult(resultEl, {
            win: true, anchor: 5, next: 11, direction: 'HIGHER', net: 100, multiplier: 1.5
        });

        expect(resultEl.classList.contains('highlow-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('J'); // 11 -> J
        expect(resultEl.innerHTML).toContain('5');
        expect(resultEl.innerHTML).toContain('Higher');
        expect(resultEl.innerHTML).toContain('1.50×');
        expect(resultEl.innerHTML).toContain('+100 credits');
    });

    test('lose on tie shows = and lost', () => {
        renderHighlowResult(resultEl, {
            win: false, anchor: 7, next: 7, direction: 'HIGHER', net: -50
        });

        expect(resultEl.classList.contains('highlow-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('=');
        expect(resultEl.innerHTML).toContain('lost');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderHighlowResult(resultEl, {
            win: true, anchor: 4, next: 12, direction: 'HIGHER', net: 100, jackpotPayout: 1500
        });

        expect(resultEl.classList.contains('highlow-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+1500 credits');
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderHighlowResult(resultEl, {
            win: false, anchor: 7, next: 7, direction: 'HIGHER', net: -50, lossTribute: 5
        });

        expect(resultEl.innerHTML).toContain('+5 to jackpot');
    });

    test('positive net flashes a chip stack on the felt; tie/loss leaves it untouched', () => {
        // Highlow has no `win` field on the response — net > 0 is the
        // win signal, so the render fn synthesises it for the helper.
        renderHighlowResult(resultEl, {
            anchor: 5, next: 11, direction: 'HIGHER', net: 100, multiplier: 1.5,
        }, tableEl);
        const stack = tableEl.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        expect(stack.querySelector('.casino-chip-payout').textContent).toBe('+100');

        tableEl.querySelectorAll('.casino-chip-stack').forEach((el) => el.remove());
        renderHighlowResult(resultEl, {
            anchor: 7, next: 7, direction: 'HIGHER', net: -50,
        }, tableEl);
        expect(tableEl.querySelector('.casino-chip-stack')).toBeNull();
    });
});
