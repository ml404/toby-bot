// Regression for the "celebratory chip flourish + win sound only fire on
// the first solo hand of the session" bug. The dedup that prevents the
// 1.5s poll loop from re-flashing the same hand used to key off
// `lastResult.handNumber` alone, but every solo hand spins up a fresh
// BlackjackTable with handNumber reset to 1 — so after the first win,
// `lastFlashedHand = 1` blocked every subsequent solo hand silently.
//
// The fix keys the dedup on (tableId, handNumber). `createFlashDedup` is
// the small DOM-agnostic factory that encodes the contract; it's exported
// on `window.TobyBlackjackSolo` so this test can exercise it directly.
require('../../main/resources/static/js/blackjack-solo');

describe('TobyBlackjackSolo.createFlashDedup', () => {
    test('fires once for a hand and dedups repeated polls of the same state', () => {
        const shouldFlash = window.TobyBlackjackSolo.createFlashDedup();
        const state = { tableId: 1, lastResult: { handNumber: 1 } };
        expect(shouldFlash(state)).toBe(true);
        expect(shouldFlash(state)).toBe(false);
        expect(shouldFlash(state)).toBe(false);
    });

    test('fires again when tableId changes — solo hands all carry handNumber=1', () => {
        // This is the regressed path: every new solo BlackjackTable starts
        // at handNumber=1, so a handNumber-only dedup blocked every win
        // after the first. tableId differs per hand and unblocks it.
        const shouldFlash = window.TobyBlackjackSolo.createFlashDedup();
        expect(shouldFlash({ tableId: 1, lastResult: { handNumber: 1 } })).toBe(true);
        expect(shouldFlash({ tableId: 2, lastResult: { handNumber: 1 } })).toBe(true);
        expect(shouldFlash({ tableId: 3, lastResult: { handNumber: 1 } })).toBe(true);
    });

    test('fires again when handNumber increments on a stable tableId (multi case)', () => {
        const shouldFlash = window.TobyBlackjackSolo.createFlashDedup();
        expect(shouldFlash({ tableId: 99, lastResult: { handNumber: 1 } })).toBe(true);
        expect(shouldFlash({ tableId: 99, lastResult: { handNumber: 2 } })).toBe(true);
        expect(shouldFlash({ tableId: 99, lastResult: { handNumber: 3 } })).toBe(true);
    });

    test('returns false when the state has no lastResult yet', () => {
        const shouldFlash = window.TobyBlackjackSolo.createFlashDedup();
        expect(shouldFlash({ tableId: 1, lastResult: null })).toBe(false);
        expect(shouldFlash({ tableId: 1 })).toBe(false);
        expect(shouldFlash(null)).toBe(false);
    });
});
