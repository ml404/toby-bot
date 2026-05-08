const { renderCoinflipResult, createBotSuspicionTracker } = require('../../main/resources/static/js/coinflip');
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

    // The bot-suspicion tracker is exercised here as a unit so the page
    // IIFE doesn't need a full DOM stand-up. The wiring inside the IIFE
    // (document mousemove + button click listeners feeding this tracker)
    // is exercised manually during web smoke.
    describe('bot-suspicion tracker', () => {
        test('first snapshot before any click reports nulls and mouseMoved=false', () => {
            const t = createBotSuspicionTracker();
            const snap = t.snapshotAndReset();
            expect(snap).toEqual({ clickX: null, clickY: null, mouseMoved: false });
        });

        test('recordClick captures clientX/clientY into the next snapshot', () => {
            const t = createBotSuspicionTracker();
            t.recordClick({ clientX: 350, clientY: 220 });
            const snap = t.snapshotAndReset();
            expect(snap.clickX).toBe(350);
            expect(snap.clickY).toBe(220);
        });

        test('recordMouseMove sets mouseMoved=true until the next snapshot', () => {
            const t = createBotSuspicionTracker();
            t.recordMouseMove();
            expect(t.snapshotAndReset().mouseMoved).toBe(true);
        });

        test('snapshotAndReset clears mouseMoved for the following bet', () => {
            // The frontend reports motion *between* bets, not since page
            // load. So the second snapshot in a row sees no movement
            // unless a new mousemove arrives between snapshots.
            const t = createBotSuspicionTracker();
            t.recordMouseMove();
            t.snapshotAndReset();
            const second = t.snapshotAndReset();
            expect(second.mouseMoved).toBe(false);
        });

        test('coords persist across snapshots until a new click overwrites', () => {
            // A new bet without a fresh click (rare — keyboard submit)
            // resends the prior coords; the backend treats that as
            // "same spot" but reset state on this bet means the streak
            // bump is moot.
            const t = createBotSuspicionTracker();
            t.recordClick({ clientX: 100, clientY: 50 });
            t.snapshotAndReset();
            const second = t.snapshotAndReset();
            expect(second.clickX).toBe(100);
            expect(second.clickY).toBe(50);

            t.recordClick({ clientX: 999, clientY: 1 });
            const third = t.snapshotAndReset();
            expect(third.clickX).toBe(999);
            expect(third.clickY).toBe(1);
        });

        test('recordClick gracefully ignores malformed events', () => {
            const t = createBotSuspicionTracker();
            t.recordClick(null);
            t.recordClick({});
            const snap = t.snapshotAndReset();
            expect(snap.clickX).toBeNull();
            expect(snap.clickY).toBeNull();
        });
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
