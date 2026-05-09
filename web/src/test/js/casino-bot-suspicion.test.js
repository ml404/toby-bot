// Pure-JS test for the shared anti-autoclicker tracker. Loaded directly
// from the static module so the per-game IIFEs (coinflip/dice/slots)
// don't need DOM scaffolding to exercise the same factory.
const TobyCasinoBotSuspicion = require('../../main/resources/static/js/casino-bot-suspicion');

describe('TobyCasinoBotSuspicion.createTracker', () => {
    test('first snapshot before any click reports nulls and mouseMoved=false', () => {
        const t = TobyCasinoBotSuspicion.createTracker();
        const snap = t.snapshotAndReset();
        expect(snap).toEqual({ clickX: null, clickY: null, mouseMoved: false });
    });

    test('recordClick captures clientX/clientY into the next snapshot', () => {
        const t = TobyCasinoBotSuspicion.createTracker();
        t.recordClick({ clientX: 350, clientY: 220 });
        const snap = t.snapshotAndReset();
        expect(snap.clickX).toBe(350);
        expect(snap.clickY).toBe(220);
    });

    test('recordMouseMove sets mouseMoved=true until the next snapshot', () => {
        const t = TobyCasinoBotSuspicion.createTracker();
        t.recordMouseMove();
        expect(t.snapshotAndReset().mouseMoved).toBe(true);
    });

    test('snapshotAndReset clears mouseMoved for the following bet', () => {
        // The frontend reports motion *between* bets, not since page
        // load. So the second snapshot in a row sees no movement
        // unless a new mousemove arrives between snapshots.
        const t = TobyCasinoBotSuspicion.createTracker();
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
        const t = TobyCasinoBotSuspicion.createTracker();
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
        const t = TobyCasinoBotSuspicion.createTracker();
        t.recordClick(null);
        t.recordClick({});
        const snap = t.snapshotAndReset();
        expect(snap.clickX).toBeNull();
        expect(snap.clickY).toBeNull();
    });

    test('exposes itself as window.TobyCasinoBotSuspicion when loaded in a browser context', () => {
        // The module file does `window.TobyCasinoBotSuspicion = api` when
        // window exists. jsdom provides one, so the assignment runs.
        expect(typeof window.TobyCasinoBotSuspicion.createTracker).toBe('function');
    });
});
