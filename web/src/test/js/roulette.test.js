// Smoke-test the pure render contract for the /spin response and the
// bet-input lock helper that gates the chip radiogroup + straight-number
// picker during the wheel animation. The IIFE in roulette.js wires the
// form, postJson, and animation; these tests only exercise the hoisted
// helpers so DOM mutations are deterministic.

const {
    renderRouletteResult,
    setBetInputsDisabled,
} = require('../../main/resources/static/js/roulette');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderRouletteResult', () => {
    let resultEl;
    let tableEl;

    beforeEach(() => {
        document.body.innerHTML =
            '<section id="t" class="roulette-table">' +
            '  <div id="r"></div>' +
            '</section>';
        tableEl = document.getElementById('t');
        resultEl = document.getElementById('r');
    });

    test('win path includes net credits, multiplier, bet label, and pocket colour', () => {
        renderRouletteResult(resultEl, {
            win: true, net: 100, multiplier: 2,
            bet: 'RED', betLabel: 'Red',
            landed: 7, color: 'RED',
        }, tableEl);

        expect(resultEl.classList.contains('roulette-result-win')).toBe(true);
        expect(resultEl.classList.contains('roulette-result-lose')).toBe(false);
        expect(resultEl.innerHTML).toContain('+100 credits');
        expect(resultEl.innerHTML).toContain('2×');
        expect(resultEl.innerHTML).toContain('Red');
        expect(resultEl.innerHTML).toContain('#7');
        expect(resultEl.innerHTML).toContain('red');
        expect(resultEl.hidden).toBe(false);
    });

    test('lose path shows the loss amount, the lose class, and the landed pocket', () => {
        renderRouletteResult(resultEl, {
            win: false, net: -50,
            bet: 'BLACK', betLabel: 'Black',
            landed: 0, color: 'GREEN',
        }, tableEl);

        expect(resultEl.classList.contains('roulette-result-lose')).toBe(true);
        expect(resultEl.classList.contains('roulette-result-win')).toBe(false);
        expect(resultEl.innerHTML).toContain('50 credits');
        expect(resultEl.innerHTML).toContain('Black');
        expect(resultEl.innerHTML).toContain('#0');
        expect(resultEl.innerHTML).toContain('green');
    });

    test('jackpot win prepends the JACKPOT banner via TobyJackpot helper', () => {
        renderRouletteResult(resultEl, {
            win: true, net: 350, multiplier: 36, jackpotPayout: 5000,
            bet: 'STRAIGHT', betLabel: 'Straight',
            landed: 17, color: 'BLACK', straightNumber: 17,
        }, tableEl);

        expect(resultEl.classList.contains('roulette-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+5000 credits');
        expect(resultEl.innerHTML).toContain('+350 credits');
        expect(resultEl.innerHTML).toContain('36×');
    });

    test('clears prior win/lose/jackpot classes between renders', () => {
        resultEl.classList.add('roulette-result-jackpot');
        resultEl.classList.add('roulette-result-lose');

        renderRouletteResult(resultEl, {
            win: true, net: 100, multiplier: 2,
            bet: 'RED', betLabel: 'Red', landed: 7, color: 'RED',
        }, tableEl);

        expect(resultEl.classList.contains('roulette-result-jackpot')).toBe(false);
        expect(resultEl.classList.contains('roulette-result-lose')).toBe(false);
        expect(resultEl.classList.contains('roulette-result-win')).toBe(true);
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderRouletteResult(resultEl, {
            win: false, net: -100, lossTribute: 10,
            bet: 'RED', betLabel: 'Red', landed: 0, color: 'GREEN',
        }, tableEl);

        expect(resultEl.classList.contains('roulette-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('100 credits');
        expect(resultEl.innerHTML).toContain('+10 to jackpot');
    });

    test('returns early on missing result element', () => {
        expect(() => renderRouletteResult(null, { win: true })).not.toThrow();
    });

    // Chip flourish has moved to casino-win-settle (the shared helper);
    // covered in casino-win-settle.test.js. renderRouletteResult now
    // only owns the result line + class toggling.
});

describe('setBetInputsDisabled', () => {
    let fieldset;
    let chips;
    let straightInput;

    beforeEach(() => {
        document.body.innerHTML =
            '<form>' +
            '  <fieldset id="bf">' +
            '    <button type="button" class="roulette-chip" aria-checked="true" data-bet="RED">Red</button>' +
            '    <button type="button" class="roulette-chip" aria-checked="false" data-bet="BLACK">Black</button>' +
            '    <button type="button" class="roulette-chip" aria-checked="false" data-bet="STRAIGHT" data-requires-number="true">Straight</button>' +
            '  </fieldset>' +
            '  <input id="rn" type="number" min="0" max="36" value="0">' +
            '</form>';
        fieldset = document.getElementById('bf');
        chips = Array.from(fieldset.querySelectorAll('.roulette-chip'));
        straightInput = document.getElementById('rn');
    });

    test('flips disabled + aria-disabled on every chip and the straight input', () => {
        setBetInputsDisabled(true, fieldset, straightInput);

        chips.forEach((c) => {
            expect(c.disabled).toBe(true);
            expect(c.getAttribute('aria-disabled')).toBe('true');
        });
        expect(straightInput.disabled).toBe(true);
    });

    test('clears disabled + aria-disabled when locking is released', () => {
        setBetInputsDisabled(true, fieldset, straightInput);
        setBetInputsDisabled(false, fieldset, straightInput);

        chips.forEach((c) => {
            expect(c.disabled).toBe(false);
            expect(c.hasAttribute('aria-disabled')).toBe(false);
        });
        expect(straightInput.disabled).toBe(false);
    });

    test('tolerates a null straightInput (picker hidden) without throwing', () => {
        expect(() => setBetInputsDisabled(true, fieldset, null)).not.toThrow();
        chips.forEach((c) => expect(c.disabled).toBe(true));
    });

    test('returns early without throwing on a null fieldset', () => {
        expect(() => setBetInputsDisabled(true, null, straightInput)).not.toThrow();
    });

    test('clicking a disabled chip does not change the aria-checked selection', () => {
        // Mid-spin lockout: the chip radiogroup must be inert until the
        // wheel settles, otherwise a player can re-pick a bet that was
        // never wagered. Browsers natively suppress click on disabled
        // <button>; the assertion just nails that contract down so a
        // future regression (e.g. swapping <button> for <div role="radio">)
        // doesn't quietly break it.
        setBetInputsDisabled(true, fieldset, straightInput);
        const black = chips.find((c) => c.dataset.bet === 'BLACK');
        black.click();

        expect(black.getAttribute('aria-checked')).toBe('false');
        const red = chips.find((c) => c.dataset.bet === 'RED');
        expect(red.getAttribute('aria-checked')).toBe('true');
    });
});
