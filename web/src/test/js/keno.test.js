const {
    renderKenoResult,
    kenoWinLineHtml,
    kenoLoseLineHtml,
} = require('../../main/resources/static/js/keno');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('kenoWinLineHtml / kenoLoseLineHtml', () => {
    test('win line includes hits/picks ratio, net, and multiplier', () => {
        const html = kenoWinLineHtml({
            picks: [1, 2, 3, 4, 5], hits: 5, net: 7990, multiplier: 800,
        });
        expect(html).toContain('5/5');
        expect(html).toContain('+7990 credits');
        expect(html).toContain('800×');
    });

    test('lose line includes hits/picks ratio and abs(net)', () => {
        const html = kenoLoseLineHtml({
            picks: [1, 2, 3, 4, 5], hits: 1, net: -50,
        });
        expect(html).toContain('1/5');
        expect(html).toContain('lost <strong>50 credits</strong>');
    });
});

describe('renderKenoResult (synchronous path)', () => {
    let resultEl, statusEl, gridEl, tableEl;

    beforeEach(() => {
        document.body.innerHTML =
            '<div id="r"></div>' +
            '<div id="s"></div>' +
            '<section id="t">' +
            '<div id="g">' +
            // Just enough cells for the tests below — values 1..30.
            Array.from({ length: 30 }, (_, i) =>
                `<button class="keno-cell" data-value="${i + 1}">${i + 1}</button>`
            ).join('') +
            '</div></section>';
        resultEl = document.getElementById('r');
        statusEl = document.getElementById('s');
        gridEl = document.getElementById('g');
        tableEl = document.getElementById('t');
    });

    function findCell(n) {
        return gridEl.querySelector(`.keno-cell[data-value="${n}"]`);
    }

    test('win lights up hit cells with .is-hit, miss cells with .is-drawn, and renders the win line', () => {
        renderKenoResult({
            resultEl, statusEl, gridEl, flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: true,
                picks: [1, 2, 3],          // 3 picks
                draws: [1, 2, 4, 5, 6, 7], // 1, 2 are hits; 4, 5, 6, 7 are misses
                hits: 2,
                multiplier: 1,
                net: 0,                    // 3-spot, 2 hits → 1× → net 0 (push-shaped win)
                payout: 100,
            },
        });

        expect(findCell(1).classList.contains('is-hit')).toBe(true);
        expect(findCell(2).classList.contains('is-hit')).toBe(true);
        expect(findCell(3).classList.contains('is-picked')).toBe(true); // picked but not drawn
        expect(findCell(4).classList.contains('is-drawn')).toBe(true);
        expect(findCell(7).classList.contains('is-drawn')).toBe(true);

        expect(resultEl.classList.contains('keno-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('2/3');
        expect(statusEl.textContent).toContain('2 of 3 hit');
    });

    test('lose path strips draws of any prior hit class and renders the lose line', () => {
        renderKenoResult({
            resultEl, statusEl, gridEl, flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: false,
                picks: [25, 26, 27],
                draws: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
                hits: 0,
                multiplier: 0,
                net: -100,
            },
        });

        expect(findCell(25).classList.contains('is-picked')).toBe(true);
        expect(findCell(25).classList.contains('is-hit')).toBe(false);
        expect(findCell(1).classList.contains('is-drawn')).toBe(true);
        expect(resultEl.classList.contains('keno-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('0/3');
    });

    test('win flashes a chip stack on the flash target; loss leaves it untouched', () => {
        renderKenoResult({
            resultEl, statusEl, gridEl, flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: true, picks: [5], draws: [5, 1, 2, 3, 4, 6, 7, 8, 9, 10],
                hits: 1, multiplier: 3.5, net: 250, payout: 350,
            },
        });
        const stack = tableEl.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        expect(stack.querySelector('.casino-chip-payout').textContent).toBe('+250');

        // Reset and re-test lose: no chip stack should be added.
        tableEl.querySelectorAll('.casino-chip-stack').forEach((el) => el.remove());
        renderKenoResult({
            resultEl, statusEl, gridEl, flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: false, picks: [5], draws: [1, 2, 3, 4, 6, 7, 8, 9, 10, 11],
                hits: 0, net: -50,
            },
        });
        expect(tableEl.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderKenoResult({
            resultEl, statusEl, gridEl, flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: true, picks: [5], draws: [5, 1, 2, 3, 4, 6, 7, 8, 9, 10],
                hits: 1, multiplier: 3.5, net: 250, payout: 350,
                jackpotPayout: 4000,
            },
        });
        expect(resultEl.classList.contains('keno-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+4000 credits');
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderKenoResult({
            resultEl, statusEl, gridEl, flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: false, picks: [5], draws: [1, 2, 3, 4, 6, 7, 8, 9, 10, 11],
                hits: 0, net: -50, lossTribute: 5,
            },
        });
        expect(resultEl.innerHTML).toContain('+5 to jackpot');
    });
});

describe('renderKenoResult (staged path)', () => {
    let resultEl, statusEl, gridEl, tableEl;

    beforeEach(() => {
        document.body.innerHTML =
            '<div id="r"></div>' +
            '<div id="s"></div>' +
            '<section id="t">' +
            '<div id="g">' +
            Array.from({ length: 30 }, (_, i) =>
                `<button class="keno-cell" data-value="${i + 1}">${i + 1}</button>`
            ).join('') +
            '</div></section>';
        resultEl = document.getElementById('r');
        statusEl = document.getElementById('s');
        gridEl = document.getElementById('g');
        tableEl = document.getElementById('t');
    });

    test('staged path holds the result line until the deal sequence completes', () => {
        jest.useFakeTimers();
        try {
            renderKenoResult({
                resultEl, statusEl, gridEl, flashTargetEl: tableEl,
                stagger: true, dealMs: 50,
                body: {
                    win: true,
                    picks: [1, 2, 3],
                    // 3 cells get drawn — first hit at t=0, miss at t=50, hit at t=100.
                    draws: [1, 99, 2],
                    hits: 2,
                    multiplier: 42,
                    net: 4100,
                    payout: 4200,
                },
            });

            // Synchronously after the call: status flips to "Drawing…",
            // result line is hidden, no draws are revealed yet.
            expect(statusEl.textContent).toBe('Drawing…');
            expect(resultEl.hidden).toBe(true);
            expect(gridEl.querySelectorAll('.is-drawn, .is-hit').length).toBe(0);

            // Halfway through the first interval — only the first draw landed.
            jest.advanceTimersByTime(25);
            expect(gridEl.querySelector('.keno-cell[data-value="1"]').classList.contains('is-hit')).toBe(true);
            expect(gridEl.querySelectorAll('.is-drawn').length).toBe(0);
            expect(resultEl.hidden).toBe(true);

            // Past the deal sequence (3 cells × 50ms = 150ms), but before
            // flashChipsOn's 2.5s safety-cleanup timer would remove the
            // chip stack.
            jest.advanceTimersByTime(200);
            expect(gridEl.querySelector('.keno-cell[data-value="2"]').classList.contains('is-hit')).toBe(true);
            expect(resultEl.hidden).toBe(false);
            expect(resultEl.classList.contains('keno-result-win')).toBe(true);
            expect(statusEl.textContent).toContain('2 of 3 hit');
            expect(tableEl.querySelector('.casino-chip-stack')).not.toBeNull();
        } finally {
            jest.useRealTimers();
        }
    });
});
