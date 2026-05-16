const { renderHorseRacingResult } = require('../../main/resources/static/js/horse-racing');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderHorseRacingResult', () => {
    let resultEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        resultEl = document.getElementById('r');
    });

    test('win surfaces the podium, multiplier, and credits won', () => {
        renderHorseRacingResult(resultEl, {
            win: true,
            pickedHorse: 3,
            bet: 'WIN',
            betLabel: 'Win',
            finishingOrder: [3, 1, 5, 2, 4, 6],
            multiplier: 5.3,
            net: 430,
            newBalance: 1430,
        });

        expect(resultEl.classList.contains('hr-result-win')).toBe(true);
        // 🥇/🥈/🥉 + horse indices for the top 3.
        expect(resultEl.innerHTML).toContain('H3');
        expect(resultEl.innerHTML).toContain('H1');
        expect(resultEl.innerHTML).toContain('H5');
        expect(resultEl.innerHTML).toContain('+430 credits');
        expect(resultEl.innerHTML).toContain('5.3×');
        expect(resultEl.innerHTML).toContain('You backed H3 to Win');
    });

    test('lose surfaces the same podium plus the lost-stake line', () => {
        renderHorseRacingResult(resultEl, {
            win: false,
            pickedHorse: 6,
            bet: 'WIN',
            betLabel: 'Win',
            finishingOrder: [1, 2, 3, 4, 5, 6],
            multiplier: 0,
            net: -100,
            newBalance: 900,
        });

        expect(resultEl.classList.contains('hr-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('H1');
        expect(resultEl.innerHTML).toContain('H2');
        expect(resultEl.innerHTML).toContain('H3');
        expect(resultEl.innerHTML).toContain('lost <strong>100 credits</strong>');
        expect(resultEl.innerHTML).toContain('You backed H6 to Win');
    });

    test('podium is taken from the top 3 finishing positions, not the whole field', () => {
        // The tail of the field (H4, H5, H6 here) should not appear with
        // a medal — only the top 3 do.
        renderHorseRacingResult(resultEl, {
            win: true,
            pickedHorse: 2,
            bet: 'PLACE',
            betLabel: 'Place',
            finishingOrder: [2, 6, 1, 4, 5, 3],
            multiplier: 2.2,
            net: 120,
            newBalance: 1120,
        });

        expect(resultEl.innerHTML).toContain('🥇 H2');
        expect(resultEl.innerHTML).toContain('🥈 H6');
        expect(resultEl.innerHTML).toContain('🥉 H1');
        // Tail of the field: no medal next to H4/H5/H3 in this render.
        expect(resultEl.innerHTML).not.toContain('🥇 H4');
        expect(resultEl.innerHTML).not.toContain('🥈 H4');
        expect(resultEl.innerHTML).not.toContain('🥉 H4');
    });

    test('Place bet shows the user-friendly bet label in the backing line', () => {
        renderHorseRacingResult(resultEl, {
            win: true,
            pickedHorse: 1,
            bet: 'PLACE',
            betLabel: 'Place',
            finishingOrder: [3, 1, 5, 2, 4, 6],
            multiplier: 1.7,
            net: 70,
            newBalance: 1070,
        });

        expect(resultEl.innerHTML).toContain('You backed H1 to Place');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderHorseRacingResult(resultEl, {
            win: true,
            pickedHorse: 1,
            bet: 'WIN',
            betLabel: 'Win',
            finishingOrder: [1, 2, 3, 4, 5, 6],
            multiplier: 3.2,
            net: 220,
            newBalance: 9220,
            jackpotPayout: 8000,
        });

        expect(resultEl.classList.contains('hr-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+220 credits');
    });

    test('lose with lossTribute appends the "+N to jackpot" suffix', () => {
        renderHorseRacingResult(resultEl, {
            win: false,
            pickedHorse: 6,
            bet: 'WIN',
            betLabel: 'Win',
            finishingOrder: [1, 2, 3, 4, 5, 6],
            multiplier: 0,
            net: -100,
            newBalance: 900,
            lossTribute: 10,
        });

        expect(resultEl.innerHTML).toContain('+10 to jackpot');
    });

    test('missing or short finishing order renders a "?" placeholder rather than crashing', () => {
        renderHorseRacingResult(resultEl, {
            win: false,
            pickedHorse: 1,
            bet: 'WIN',
            betLabel: 'Win',
            // Server-side bug: empty order. The lane-animation guard
            // skips the settle, but the result line must still render.
            finishingOrder: [],
            multiplier: 0,
            net: -50,
            newBalance: 450,
        });

        expect(resultEl.classList.contains('hr-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('?');
    });

    test('returns early when result element is null (no throw)', () => {
        expect(() => renderHorseRacingResult(null, {
            win: true,
            pickedHorse: 1,
            bet: 'WIN',
            finishingOrder: [1, 2, 3, 4, 5, 6],
            multiplier: 3.2,
            net: 220,
        })).not.toThrow();
    });
});
