// Smoke-test the pure render contract for the /spin response. The
// IIFE in slots.js wires the form, postJson, and animation; this test
// only exercises the render function so DOM mutations are deterministic.

const { renderSlotsResult } = require('../../main/resources/static/js/slots');
require('../../main/resources/static/js/casino-jackpot');

describe('renderSlotsResult', () => {
    let resultEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        resultEl = document.getElementById('r');
    });

    test('renders a win line with the multiplier and net', () => {
        renderSlotsResult(resultEl, {
            win: true, multiplier: 5, net: 400, symbols: ['🍒', '🍒', '🍒']
        });

        expect(resultEl.classList.contains('slots-result-win')).toBe(true);
        expect(resultEl.classList.contains('slots-result-lose')).toBe(false);
        expect(resultEl.innerHTML).toContain('+400 credits');
        expect(resultEl.innerHTML).toContain('5×');
        expect(resultEl.hidden).toBe(false);
    });

    test('lose path shows the loss amount and the lose class', () => {
        renderSlotsResult(resultEl, { win: false, net: -100, symbols: ['🍒', '🍋', '⭐'] });

        expect(resultEl.classList.contains('slots-result-lose')).toBe(true);
        expect(resultEl.classList.contains('slots-result-win')).toBe(false);
        expect(resultEl.innerHTML).toContain('100 credits');
    });

    test('jackpot win prepends the JACKPOT banner via TobyJackpot helper', () => {
        renderSlotsResult(resultEl, {
            win: true, multiplier: 30, net: 2900, jackpotPayout: 5000,
            symbols: ['⭐', '⭐', '⭐']
        });

        expect(resultEl.classList.contains('slots-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML.startsWith('🎰')).toBe(true);
        expect(resultEl.innerHTML).toContain('+5000 credits');
        expect(resultEl.innerHTML).toContain('+2900 credits');
    });

    test('clears prior win/lose/jackpot classes between renders', () => {
        resultEl.classList.add('slots-result-jackpot');
        resultEl.classList.add('slots-result-lose');

        renderSlotsResult(resultEl, { win: true, multiplier: 2, net: 100, symbols: [] });

        expect(resultEl.classList.contains('slots-result-jackpot')).toBe(false);
        expect(resultEl.classList.contains('slots-result-lose')).toBe(false);
        expect(resultEl.classList.contains('slots-result-win')).toBe(true);
    });

    test('returns early on missing result element', () => {
        expect(() => renderSlotsResult(null, { win: true })).not.toThrow();
    });
});
