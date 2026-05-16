const {
    renderHorseRacingResult,
    createHorseRacingAnimator,
    HR_RACE_MS,
    HR_STAGGER_MS,
    HR_MAX_FINISH_MS,
} = require('../../main/resources/static/js/horse-racing');
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

describe('createHorseRacingAnimator', () => {
    let track;
    let runners;
    let resultEl;
    let sounds;
    let scrolledOnce;

    function setUpDom() {
        document.body.innerHTML = `
            <div id="hr-track">
                <div class="hr-lane" data-horse="1"><div class="hr-lane-rail"><span class="hr-runner">🐎</span></div></div>
                <div class="hr-lane" data-horse="2"><div class="hr-lane-rail"><span class="hr-runner">🐴</span></div></div>
                <div class="hr-lane" data-horse="3"><div class="hr-lane-rail"><span class="hr-runner">🐎</span></div></div>
                <div class="hr-lane" data-horse="4"><div class="hr-lane-rail"><span class="hr-runner">🐴</span></div></div>
                <div class="hr-lane" data-horse="5"><div class="hr-lane-rail"><span class="hr-runner">🐎</span></div></div>
                <div class="hr-lane" data-horse="6"><div class="hr-lane-rail"><span class="hr-runner">🐴</span></div></div>
            </div>
            <div id="hr-result"></div>
        `;
        track = document.getElementById('hr-track');
        // jsdom doesn't implement scrollIntoView; stub it so we can
        // verify startAnimation tried to scroll the track on submit.
        scrolledOnce = false;
        track.scrollIntoView = function () { scrolledOnce = true; };
        runners = Array.from(track.querySelectorAll('.hr-runner'));
        resultEl = document.getElementById('hr-result');
        sounds = { played: [], play: function (name) { this.played.push(name); } };
    }

    // Deterministic RNG so the warm-up jitter assertions are stable.
    // Returns 0, 0.25, 0.5, 0.75, 0.1, 0.35, 0.6, 0.85, ... — pairs of
    // (targetPct, duration) draws so each lane gets a different pair.
    function makeSeededRng() {
        let i = 0;
        const seq = [0.0, 0.25, 0.5, 0.75, 0.1, 0.35, 0.6, 0.85, 0.2, 0.45, 0.7, 0.95];
        return function () {
            const v = seq[i % seq.length];
            i += 1;
            return v;
        };
    }

    function build(overrides) {
        return createHorseRacingAnimator(Object.assign({
            runners: runners,
            track: track,
            resultEl: resultEl,
            fieldSize: 6,
            sounds: sounds,
            rng: makeSeededRng(),
        }, overrides || {}));
    }

    beforeEach(() => {
        setUpDom();
    });

    afterEach(() => {
        jest.useRealTimers();
    });

    test('startAnimation lands each runner at a warm-up target inside [30%, 50%) with per-lane jitter', () => {
        const a = build();
        a.startAnimation();

        const lefts = runners.map(r => r.style.left);
        // Every runner has been issued a `left: calc(NN.N% - 1.6rem)` —
        // pre-jitter the calc shape locks "advance to mid-rail" semantics.
        lefts.forEach(left => {
            expect(left).toMatch(/^calc\(\d+(?:\.\d+)?% - 1\.6rem\)$/);
            const pct = parseFloat(left.match(/calc\((\d+(?:\.\d+)?)% - 1\.6rem\)/)[1]);
            expect(pct).toBeGreaterThanOrEqual(30);
            expect(pct).toBeLessThan(50);
        });
        // At least one lane differs from another so they don't move in
        // lockstep — the seeded RNG guarantees this.
        const uniquePositions = new Set(lefts);
        expect(uniquePositions.size).toBeGreaterThan(1);
    });

    test('startAnimation issues a per-lane warm-up duration inside [4000ms, 7000ms)', () => {
        const a = build();
        a.startAnimation();

        runners.forEach(r => {
            const m = r.style.transition.match(/^left (\d+(?:\.\d+)?)ms/);
            expect(m).not.toBeNull();
            const duration = parseFloat(m[1]);
            expect(duration).toBeGreaterThanOrEqual(4000);
            expect(duration).toBeLessThan(7000);
        });
    });

    test('startAnimation scrolls the track into view and plays the deal cue', () => {
        const a = build();
        a.startAnimation();
        expect(scrolledOnce).toBe(true);
        expect(sounds.played).toContain('deal');
    });

    test('startAnimation resets any previously-finished lanes', () => {
        // Simulate a prior race that already ended.
        runners[2].classList.add('hr-runner-finished');
        runners[2].style.left = 'calc(100% - 2rem)';

        const a = build();
        a.startAnimation();

        expect(runners[2].classList.contains('hr-runner-finished')).toBe(false);
    });

    test('stopAnimation redirects every horse to the finish line', () => {
        const a = build();
        a.startAnimation();
        a.stopAnimation(null, { finishingOrder: [3, 1, 5, 2, 4, 6] });

        runners.forEach(r => {
            expect(r.style.left).toBe('calc(100% - 2rem)');
        });
    });

    test('stopAnimation assigns rank-staggered durations: winner takes RACE_MS, last takes RACE_MS + 5×STAGGER_MS', () => {
        const order = [3, 1, 5, 2, 4, 6];  // H3 wins, H6 last
        const a = build();
        a.startAnimation();
        a.stopAnimation(null, { finishingOrder: order });

        // Each rank → expected duration ms.
        order.forEach((horseIdx, rank) => {
            const expected = HR_RACE_MS + rank * HR_STAGGER_MS;
            const runner = runners[horseIdx - 1];
            const m = runner.style.transition.match(/^left (\d+)ms/);
            expect(m).not.toBeNull();
            expect(parseInt(m[1], 10)).toBe(expected);
        });
    });

    test('stopAnimation crowns each horse with the finished glow at its own rank-staggered time', () => {
        jest.useFakeTimers();
        const order = [3, 1, 5, 2, 4, 6];
        const a = build();
        a.startAnimation();
        a.stopAnimation(null, { finishingOrder: order });

        // Right after the call, no horse has finished yet.
        runners.forEach(r => {
            expect(r.classList.contains('hr-runner-finished')).toBe(false);
        });

        // Just before the winner is due — still nobody crowned.
        jest.advanceTimersByTime(HR_RACE_MS - 1);
        runners.forEach(r => {
            expect(r.classList.contains('hr-runner-finished')).toBe(false);
        });

        // Winner crosses at exactly RACE_MS. Only the winner is crowned;
        // the rest are still mid-stride.
        jest.advanceTimersByTime(1);
        expect(runners[order[0] - 1].classList.contains('hr-runner-finished')).toBe(true);
        order.slice(1).forEach(horseIdx => {
            expect(runners[horseIdx - 1].classList.contains('hr-runner-finished')).toBe(false);
        });

        // Advance through every rank and verify each one lands at its
        // expected tick.
        for (let rank = 1; rank < order.length; rank++) {
            jest.advanceTimersByTime(HR_STAGGER_MS);
            for (let r = 0; r <= rank; r++) {
                expect(runners[order[r] - 1].classList.contains('hr-runner-finished')).toBe(true);
            }
            for (let r = rank + 1; r < order.length; r++) {
                expect(runners[order[r] - 1].classList.contains('hr-runner-finished')).toBe(false);
            }
        }
    });

    test('stopAnimation snapshots the current visual `left` before redirecting (no jump-back-to-zero)', () => {
        // Pre-set each runner's left so getComputedStyle reads a non-
        // zero value, mimicking mid-warm-up. The snapshot dance should
        // freeze the runner at that value before applying the new
        // transition — otherwise the runner would visually jump backward
        // to wherever the previous transition's start was.
        runners.forEach((r, i) => { r.style.left = (10 + i * 5) + 'px'; });

        const a = build();
        // Skip startAnimation so the runners' style.left stays at our
        // hand-set values (startAnimation would overwrite them).
        a.stopAnimation(null, { finishingOrder: [1, 2, 3, 4, 5, 6] });

        // Every runner's transition target is the finish line, with no
        // intermediate `transition: none` reset visible after the
        // snapshot — the final inline style holds the redirect target
        // and the rank-specific duration.
        runners.forEach((r, i) => {
            expect(r.style.left).toBe('calc(100% - 2rem)');
            expect(r.style.transition).toMatch(/^left \d+ms cubic-bezier/);
        });
    });

    test('stopAnimation no-ops gracefully on a missing or short finishing order', () => {
        const a = build();
        a.startAnimation();
        const beforeLefts = runners.map(r => r.style.left);

        // Short order: ignored. Warm-up positions remain.
        a.stopAnimation(null, { finishingOrder: [1, 2, 3] });
        runners.forEach((r, i) => {
            expect(r.style.left).toBe(beforeLefts[i]);
        });

        // Null body: ignored.
        a.stopAnimation(null, null);
        runners.forEach((r, i) => {
            expect(r.style.left).toBe(beforeLefts[i]);
        });

        // Missing finishingOrder field: ignored.
        a.stopAnimation(null, { ok: true });
        runners.forEach((r, i) => {
            expect(r.style.left).toBe(beforeLefts[i]);
        });
    });

    test('stopAnimation plays the click cue at RACE_MS, not earlier', () => {
        jest.useFakeTimers();
        const a = build();
        a.startAnimation();
        const before = sounds.played.length;
        a.stopAnimation(null, { finishingOrder: [1, 2, 3, 4, 5, 6] });

        jest.advanceTimersByTime(HR_RACE_MS - 1);
        expect(sounds.played.length).toBe(before);

        jest.advanceTimersByTime(1);
        expect(sounds.played[sounds.played.length - 1]).toBe('click');
    });

    test('renderResult returns a Promise that resolves only after MAX_FINISH_MS', async () => {
        jest.useFakeTimers();
        const a = build();
        const onResolve = jest.fn();
        const ret = a.renderResult({
            win: true,
            pickedHorse: 1,
            bet: 'WIN',
            betLabel: 'Win',
            finishingOrder: [1, 2, 3, 4, 5, 6],
            multiplier: 3.2,
            net: 220,
        });
        expect(ret && typeof ret.then).toBe('function');
        ret.then(onResolve);

        // Just before the gate elapses: still pending, and the result
        // line is empty — the podium hasn't been revealed yet.
        jest.advanceTimersByTime(HR_MAX_FINISH_MS - 1);
        await Promise.resolve();
        expect(onResolve).not.toHaveBeenCalled();
        expect(resultEl.innerHTML).toBe('');

        // One more ms: gate elapses, Promise resolves, result line paints.
        jest.advanceTimersByTime(1);
        await Promise.resolve();
        await Promise.resolve();
        expect(onResolve).toHaveBeenCalledTimes(1);
        expect(resultEl.innerHTML).toContain('+220 credits');
    });

    test('renderResult uses the per-instance maxFinishMs from a custom raceMs/staggerMs', async () => {
        jest.useFakeTimers();
        const a = build({ raceMs: 1000, staggerMs: 100 });
        // 1000 + 5 * 100 = 1500ms gate.
        expect(a.maxFinishMs).toBe(1500);

        const onResolve = jest.fn();
        a.renderResult({
            win: false,
            pickedHorse: 1,
            bet: 'WIN',
            betLabel: 'Win',
            finishingOrder: [2, 3, 4, 5, 6, 1],
            multiplier: 0,
            net: -100,
        }).then(onResolve);

        jest.advanceTimersByTime(1499);
        await Promise.resolve();
        expect(onResolve).not.toHaveBeenCalled();

        jest.advanceTimersByTime(1);
        await Promise.resolve();
        await Promise.resolve();
        expect(onResolve).toHaveBeenCalledTimes(1);
    });

    test('the runners cross the line in the server-returned order regardless of pre-redirect positions', () => {
        jest.useFakeTimers();
        // Pre-set positions so H6 is ahead and H1 is behind — but the
        // server says H1 finishes first. The finished class must still
        // fire on H1 before H6, because the rank-staggered durations
        // are what determines the visual order.
        runners[0].style.left = '5px';    // H1 (behind)
        runners[5].style.left = '200px';  // H6 (ahead)

        const a = build();
        a.stopAnimation(null, { finishingOrder: [1, 2, 3, 4, 5, 6] });

        // H1 (winner) crowns first.
        jest.advanceTimersByTime(HR_RACE_MS);
        expect(runners[0].classList.contains('hr-runner-finished')).toBe(true);
        expect(runners[5].classList.contains('hr-runner-finished')).toBe(false);

        // H6 (last) crowns at the very end.
        jest.advanceTimersByTime(5 * HR_STAGGER_MS);
        expect(runners[5].classList.contains('hr-runner-finished')).toBe(true);
    });

    test('HR_MAX_FINISH_MS export matches the formula for the default field of six', () => {
        // Sanity check on the public constant — this is what
        // casino-game.js's `finishSettle` is gated by, so it can't drift
        // away from the RACE_MS + (FIELD-1) * STAGGER_MS arithmetic.
        expect(HR_MAX_FINISH_MS).toBe(HR_RACE_MS + 5 * HR_STAGGER_MS);
    });
});
