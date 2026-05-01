/**
 * Pure-DOM unit test for the shot-clock countdown. We can't load the
 * full poker-table.js because it relies on the page's `#main` data-*
 * attributes and a fetch-driven loop; instead we re-implement the
 * tiny `renderShotClock` function here mirror-style and assert on its
 * DOM behaviour. The production source is the canonical version —
 * this test serves as a contract check that the rendering shape (hide
 * when 0/null, "Ns" countdown, "Time!" at zero) is what we want.
 */
describe('poker-table shot-clock countdown', () => {
    let shotClockEl;
    let shotClockTicker;

    function renderShotClock(state) {
        if (!shotClockEl) return;
        if (shotClockTicker) { clearInterval(shotClockTicker); shotClockTicker = null; }
        const deadline = state.currentActorDeadlineEpochMillis;
        if (!state.shotClockSeconds || !deadline || state.phase === 'WAITING') {
            shotClockEl.hidden = true;
            shotClockEl.textContent = '';
            return;
        }
        function tick() {
            const remainingMs = deadline - Date.now();
            if (remainingMs <= 0) {
                shotClockEl.textContent = 'Time!';
                if (shotClockTicker) { clearInterval(shotClockTicker); shotClockTicker = null; }
                return;
            }
            shotClockEl.textContent = Math.ceil(remainingMs / 1000) + 's';
        }
        shotClockEl.hidden = false;
        tick();
        shotClockTicker = setInterval(tick, 1000);
    }

    beforeEach(() => {
        document.body.innerHTML = '<div id="clock" hidden></div>';
        shotClockEl = document.getElementById('clock');
        shotClockTicker = null;
        jest.useFakeTimers();
        jest.setSystemTime(new Date('2026-05-01T10:00:00Z'));
    });

    afterEach(() => {
        if (shotClockTicker) clearInterval(shotClockTicker);
        jest.useRealTimers();
    });

    test('stays hidden when shot clock is disabled (0)', () => {
        renderShotClock({
            phase: 'PRE_FLOP',
            shotClockSeconds: 0,
            currentActorDeadlineEpochMillis: Date.now() + 30_000,
        });
        expect(shotClockEl.hidden).toBe(true);
        expect(shotClockEl.textContent).toBe('');
    });

    test('stays hidden when no actor deadline is set', () => {
        renderShotClock({
            phase: 'PRE_FLOP',
            shotClockSeconds: 30,
            currentActorDeadlineEpochMillis: null,
        });
        expect(shotClockEl.hidden).toBe(true);
    });

    test('stays hidden when phase is WAITING (between hands)', () => {
        renderShotClock({
            phase: 'WAITING',
            shotClockSeconds: 30,
            currentActorDeadlineEpochMillis: Date.now() + 30_000,
        });
        expect(shotClockEl.hidden).toBe(true);
    });

    test('renders rounded-up seconds remaining when armed', () => {
        renderShotClock({
            phase: 'PRE_FLOP',
            shotClockSeconds: 30,
            currentActorDeadlineEpochMillis: Date.now() + 12_400,
        });
        expect(shotClockEl.hidden).toBe(false);
        // 12.4s remaining → ceil = 13s.
        expect(shotClockEl.textContent).toBe('13s');
    });

    test('ticks down every second', () => {
        renderShotClock({
            phase: 'PRE_FLOP',
            shotClockSeconds: 30,
            currentActorDeadlineEpochMillis: Date.now() + 5000,
        });
        expect(shotClockEl.textContent).toBe('5s');
        jest.advanceTimersByTime(1000);
        expect(shotClockEl.textContent).toBe('4s');
        jest.advanceTimersByTime(2000);
        expect(shotClockEl.textContent).toBe('2s');
    });

    test('shows "Time!" once the deadline elapses', () => {
        renderShotClock({
            phase: 'PRE_FLOP',
            shotClockSeconds: 30,
            currentActorDeadlineEpochMillis: Date.now() + 1500,
        });
        expect(shotClockEl.textContent).toBe('2s');
        jest.advanceTimersByTime(2000);
        expect(shotClockEl.textContent).toBe('Time!');
        // Further ticks must not flip it back to a positive number.
        jest.advanceTimersByTime(5000);
        expect(shotClockEl.textContent).toBe('Time!');
    });

    test('switching from "armed" to "WAITING" clears the previous tick', () => {
        renderShotClock({
            phase: 'PRE_FLOP',
            shotClockSeconds: 30,
            currentActorDeadlineEpochMillis: Date.now() + 30_000,
        });
        expect(shotClockEl.hidden).toBe(false);
        renderShotClock({
            phase: 'WAITING',
            shotClockSeconds: 30,
            currentActorDeadlineEpochMillis: null,
        });
        expect(shotClockEl.hidden).toBe(true);
        expect(shotClockEl.textContent).toBe('');
        // Advance past the prior deadline — the disabled state must
        // not start counting again.
        jest.advanceTimersByTime(60_000);
        expect(shotClockEl.textContent).toBe('');
    });
});
