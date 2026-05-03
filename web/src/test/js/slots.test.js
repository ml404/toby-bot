// Smoke-test the pure render contract for the /spin response. The
// IIFE in slots.js wires the form, postJson, and animation; this test
// only exercises the render function so DOM mutations are deterministic.

const { renderSlotsResult } = require('../../main/resources/static/js/slots');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderSlotsResult', () => {
    let resultEl;
    let machineEl;
    let reels;

    beforeEach(() => {
        document.body.innerHTML =
            '<div id="r"></div>' +
            '<section id="m">' +
            '  <div class="slots-reel" id="r0"></div>' +
            '  <div class="slots-reel" id="r1"></div>' +
            '  <div class="slots-reel" id="r2"></div>' +
            '</section>';
        resultEl = document.getElementById('r');
        machineEl = document.getElementById('m');
        reels = ['r0', 'r1', 'r2'].map((id) => document.getElementById(id));
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

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderSlotsResult(resultEl, { win: false, net: -100, symbols: [], lossTribute: 10 });

        expect(resultEl.classList.contains('slots-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('100 credits');
        expect(resultEl.innerHTML).toContain('+10 to jackpot');
        expect(resultEl.innerHTML).toContain('casino-loss-tribute');
    });

    test('lose with no lossTribute renders no suffix', () => {
        renderSlotsResult(resultEl, { win: false, net: -100, symbols: [] });

        expect(resultEl.innerHTML).not.toContain('to jackpot');
    });

    test('win lights up every reel with .win-cell and drops a chip stack on the machine', () => {
        renderSlotsResult(resultEl, {
            win: true, multiplier: 5, net: 400, symbols: ['🍒', '🍒', '🍒']
        }, machineEl, reels);

        reels.forEach((r) => expect(r.classList.contains('win-cell')).toBe(true));
        const stack = machineEl.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        expect(stack.querySelector('.casino-chip-payout').textContent).toBe('+400');
    });

    test('lose strips any prior .win-cell highlight and leaves the machine clean', () => {
        // Pretend the previous spin won — the new lose render should clear it.
        reels.forEach((r) => r.classList.add('win-cell'));
        renderSlotsResult(resultEl, {
            win: false, net: -100, symbols: ['🍒', '🍋', '⭐']
        }, machineEl, reels);

        reels.forEach((r) => expect(r.classList.contains('win-cell')).toBe(false));
        expect(machineEl.querySelector('.casino-chip-stack')).toBeNull();
    });
});
