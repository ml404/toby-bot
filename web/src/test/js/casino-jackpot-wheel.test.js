// Coverage for the JS-side wheel renderer + spin animation. We exercise
// the read/parse path (`readSegments`), the spoke-allocation +
// placement math, the tier labelling, and the `spinTo` lifecycle
// (overlay reveal, SPIN-button gating, pool-banner hold/release on
// click, transitionend settle, dismiss-without-spin). Animation timing
// is shortcut by directly firing `transitionend` rather than waiting on
// real CSS transitions (jsdom doesn't run them anyway).

const wheel = require('../../main/resources/static/js/casino-jackpot-wheel');
const jackpot = require('../../main/resources/static/js/casino-jackpot');

const { readSegments, allocateSpokes, placeSpokes, render, tierLabel, spinTo, SPOKE_COUNT } = wheel;

describe('readSegments', () => {
    afterEach(() => { delete window.__jackpotWheel; });

    test('returns [] when the global is unset', () => {
        expect(readSegments()).toEqual([]);
    });

    test('returns [] when the global is empty', () => {
        window.__jackpotWheel = [];
        expect(readSegments()).toEqual([]);
    });

    test('passes through well-formed segments', () => {
        window.__jackpotWheel = [
            { weight: 80, payoutPct: 1 },
            { weight: 10, payoutPct: 5 },
        ];
        expect(readSegments()).toEqual([
            { weight: 80, payoutPct: 1 },
            { weight: 10, payoutPct: 5 },
        ]);
    });

    test('filters malformed rows defensively', () => {
        window.__jackpotWheel = [
            { weight: 10, payoutPct: 5 },
            { weight: 0, payoutPct: 5 },       // bad weight
            { weight: 5, payoutPct: 0 },       // bad pct
            null,
            { weight: 5 },                     // missing pct
            { payoutPct: 5 },                  // missing weight
            { weight: 'abc', payoutPct: 5 },   // wrong type
        ];
        expect(readSegments()).toEqual([{ weight: 10, payoutPct: 5 }]);
    });
});

describe('tierLabel', () => {
    test('returns increasingly louder labels by pct', () => {
        expect(tierLabel(1)).toContain('Pity');
        expect(tierLabel(5)).toContain('Nice');
        expect(tierLabel(10)).toContain('BIG WIN');
        expect(tierLabel(50)).toContain('MEGA JACKPOT');
    });
});

describe('allocateSpokes', () => {
    test('distributes the default segments to SPOKE_COUNT total spokes', () => {
        const counts = allocateSpokes([
            { weight: 80, payoutPct: 1 },
            { weight: 10, payoutPct: 5 },
            { weight: 5, payoutPct: 10 },
            { weight: 4, payoutPct: 20 },
            { weight: 1, payoutPct: 50 },
        ]);
        expect(counts.reduce((s, c) => s + c, 0)).toBe(SPOKE_COUNT);
        // Every tier with weight > 0 gets at least one spoke so rare
        // tiers stay visible on the rim.
        counts.forEach(c => expect(c).toBeGreaterThanOrEqual(1));
        // Heaviest tier gets most spokes.
        expect(counts[0]).toBeGreaterThan(counts[1]);
        expect(counts[0]).toBeGreaterThan(counts[4]);
    });

    test('returns [] when segments are empty or weights are zero', () => {
        expect(allocateSpokes([])).toEqual([]);
        expect(allocateSpokes([{ weight: 0, payoutPct: 1 }])).toEqual([]);
    });

    test('handles single-tier wheels', () => {
        const counts = allocateSpokes([{ weight: 1, payoutPct: 30 }]);
        expect(counts).toEqual([SPOKE_COUNT]);
    });
});

describe('placeSpokes', () => {
    test('places exactly the requested spoke counts', () => {
        const slots = placeSpokes([19, 2, 1, 1, 1]);
        expect(slots).toHaveLength(24);
        const seen = [0, 0, 0, 0, 0];
        slots.forEach(s => seen[s]++);
        expect(seen).toEqual([19, 2, 1, 1, 1]);
    });

    test('interleaves small tiers so they are not adjacent', () => {
        // Two evenly-sized tiers should alternate around the rim
        // rather than clumping into two halves.
        const slots = placeSpokes([12, 12]);
        // No more than 2 adjacent slots should share the same tier.
        let maxRun = 0, run = 0, prev = -1;
        for (const s of slots) {
            if (s === prev) run++; else run = 1;
            if (run > maxRun) maxRun = run;
            prev = s;
        }
        expect(maxRun).toBeLessThanOrEqual(2);
    });
});

describe('render', () => {
    test('emits SPOKE_COUNT paths and labels', () => {
        const rotor = document.createElementNS('http://www.w3.org/2000/svg', 'g');
        const segments = [
            { weight: 80, payoutPct: 1 },
            { weight: 10, payoutPct: 5 },
            { weight: 5, payoutPct: 10 },
            { weight: 4, payoutPct: 20 },
            { weight: 1, payoutPct: 50 },
        ];
        const spokes = render(segments, rotor);
        expect(spokes).toHaveLength(SPOKE_COUNT);
        expect(rotor.querySelectorAll('path').length).toBe(SPOKE_COUNT);
        expect(rotor.querySelectorAll('text').length).toBe(SPOKE_COUNT);
        // Every tier with weight > 0 must be represented at least once.
        const tierSet = new Set(spokes.map(s => s.tierIndex));
        expect(tierSet.size).toBe(segments.length);
    });

    test('labels each spoke with its tier payout pct', () => {
        const rotor = document.createElementNS('http://www.w3.org/2000/svg', 'g');
        render([{ weight: 1, payoutPct: 30 }], rotor);
        const labels = rotor.querySelectorAll('text');
        labels.forEach(t => expect(t.textContent).toBe('30%'));
    });
});

describe('spinTo', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <div id="jackpot-wheel-overlay" hidden>
                <div class="jackpot-wheel-panel">
                    <div class="jackpot-wheel-stage">
                        <svg viewBox="-120 -120 240 240">
                            <g class="jackpot-wheel-rotor"></g>
                        </svg>
                        <button type="button" class="jackpot-wheel-spin-btn">SPIN</button>
                        <div class="jackpot-wheel-result"></div>
                    </div>
                </div>
            </div>
            <div class="casino-jackpot-banner"><strong>0</strong></div>
        `;
        window.__jackpotWheel = [
            { weight: 80, payoutPct: 1 },
            { weight: 10, payoutPct: 5 },
            { weight: 5, payoutPct: 10 },
            { weight: 4, payoutPct: 20 },
            { weight: 1, payoutPct: 50 },
        ];
        // Make TobyJackpot's hold/release visible to the wheel; without
        // it the wheel's pool-banner coordination wouldn't fire.
        window.TobyJackpot = jackpot;
        // Stub requestAnimationFrame to run synchronously so the
        // rotation-set step in `startSpin` actually fires under jsdom.
        window.requestAnimationFrame = cb => cb(0);
        // Default to no reduced-motion preference.
        window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
    });

    afterEach(() => {
        delete window.__jackpotWheel;
        delete window.TobyJackpot;
        delete window.requestAnimationFrame;
        delete window.matchMedia;
    });

    test('reveals the overlay but does not rotate before SPIN is clicked', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        spinTo(4, 500, 50, () => {});
        expect(overlay.hidden).toBe(false);
        // Rotor snapped to 0 — no rotation applied yet.
        expect(rotor.style.transform).toBe('rotate(0deg)');
        // Button is enabled and ready.
        expect(spinBtn.disabled).toBe(false);
    });

    test('rotates the rotor to a target spoke for the chosen tier after click', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        spinTo(4, 500, 50, () => {});
        spinBtn.click();
        // Final angle = 4 full rotations - the target spoke's midpoint.
        // With 24 spokes the midpoint is one of (i + 0.5) * 15 = 7.5,
        // 22.5, 37.5, … 352.5°. So the rotation should end as
        // 1440 - one of those — i.e. 1432.5° down to 1087.5°.
        const m = rotor.style.transform.match(/rotate\(([-\d.]+)deg\)/);
        expect(m).not.toBeNull();
        const angle = parseFloat(m[1]);
        expect(angle).toBeGreaterThanOrEqual(1080);
        expect(angle).toBeLessThanOrEqual(1440);
    });

    test('disables the SPIN button while spinning', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        spinTo(0, 12, 1, () => {});
        spinBtn.click();
        expect(spinBtn.disabled).toBe(true);
    });

    test('paints the tier label on the result element when settled', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const resultEl = overlay.querySelector('.jackpot-wheel-result');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        spinTo(0, 12, 1, () => {});
        spinBtn.click();
        // Force the settle path manually — jsdom doesn't dispatch
        // transitionend on its own.
        rotor.dispatchEvent(new Event('transitionend'));
        expect(resultEl.textContent).toContain('Pity prize');
        expect(resultEl.textContent).toContain('+12 credits');
    });

    test('invokes onSettle exactly once on transitionend', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        const settled = jest.fn();
        spinTo(2, 100, 10, settled);
        spinBtn.click();
        rotor.dispatchEvent(new Event('transitionend'));
        // The internal fallback timeout might also call settle, but the
        // module's `settled` guard dedupes so onSettle fires once.
        expect(settled).toHaveBeenCalledTimes(1);
    });

    test('is a no-op (calls onSettle synchronously) when overlay is missing', () => {
        document.getElementById('jackpot-wheel-overlay').remove();
        const settled = jest.fn();
        spinTo(0, 50, 1, settled);
        expect(settled).toHaveBeenCalledTimes(1);
    });

    test('is a no-op when the tier index is out of range', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const settled = jest.fn();
        spinTo(99, 50, 1, settled);
        expect(settled).toHaveBeenCalledTimes(1);
        expect(overlay.hidden).toBe(true);
    });

    test('clicking the backdrop before SPIN dismisses and calls onSettle', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const settled = jest.fn();
        spinTo(0, 50, 1, settled);
        // Simulate backdrop click — target must be the overlay itself.
        const ev = new Event('click', { bubbles: true });
        Object.defineProperty(ev, 'target', { value: overlay });
        overlay.dispatchEvent(ev);
        expect(overlay.hidden).toBe(true);
        expect(settled).toHaveBeenCalledTimes(1);
    });

    test('clicking SPIN holds the pool banner; settle releases it', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        jackpot.releasePoolBanner();
        spinTo(0, 50, 1, () => {});
        // Before the player clicks SPIN, the banner is NOT held — an
        // update flows through normally so the page stays live.
        jackpot.updatePoolBanner({ jackpotPool: 500 });
        const strong = document.querySelector('.casino-jackpot-banner strong');
        expect(strong.textContent).toBe('500');
        // Once SPIN is pressed, the hold kicks in.
        spinBtn.click();
        jackpot.updatePoolBanner({ jackpotPool: 999 });
        expect(strong.textContent).toBe('500'); // still held
        rotor.dispatchEvent(new Event('transitionend'));
        expect(strong.textContent).toBe('999'); // released + flushed
    });

    test('reduced-motion: SPIN click settles immediately without transition', () => {
        window.matchMedia = (q) => ({
            matches: q === '(prefers-reduced-motion: reduce)',
            addEventListener() {}, removeEventListener() {}
        });
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const resultEl = overlay.querySelector('.jackpot-wheel-result');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        const settled = jest.fn();
        spinTo(0, 12, 1, settled);
        spinBtn.click();
        // Result line is painted synchronously; no transitionend needed.
        expect(resultEl.textContent).toContain('Pity prize');
        expect(settled).toHaveBeenCalledTimes(1);
    });
});
