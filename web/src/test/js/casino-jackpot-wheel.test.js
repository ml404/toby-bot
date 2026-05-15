// Coverage for the JS-side wheel renderer + spin animation. We exercise
// the read/parse path (`readSegments`), the SVG render math, the tier
// labelling, and the `spinTo` lifecycle (overlay show, pool-banner
// hold, transitionend settle, dismiss). The animation timing is
// shortcut by directly firing `transitionend` rather than waiting on
// real CSS transitions (jsdom doesn't run them anyway).

const wheel = require('../../main/resources/static/js/casino-jackpot-wheel');
const jackpot = require('../../main/resources/static/js/casino-jackpot');

const { readSegments, render, tierLabel, spinTo } = wheel;

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

describe('render', () => {
    test('emits one path + one label per segment, sized by weight', () => {
        const rotor = document.createElementNS('http://www.w3.org/2000/svg', 'g');
        const segments = [
            { weight: 80, payoutPct: 1 },
            { weight: 10, payoutPct: 5 },
            { weight: 5, payoutPct: 10 },
            { weight: 4, payoutPct: 20 },
            { weight: 1, payoutPct: 50 },
        ];
        const angles = render(segments, rotor);
        expect(rotor.querySelectorAll('path').length).toBe(5);
        expect(rotor.querySelectorAll('text').length).toBe(5);
        // 80/100 = 288 degrees for the first wedge.
        expect(angles[0].end - angles[0].start).toBeCloseTo(288, 1);
        expect(angles[4].end - angles[4].start).toBeCloseTo(3.6, 1);
    });

    test('shows the payout pct on each label', () => {
        const rotor = document.createElementNS('http://www.w3.org/2000/svg', 'g');
        render([{ weight: 1, payoutPct: 30 }], rotor);
        expect(rotor.querySelector('text').textContent).toBe('30%');
    });
});

describe('spinTo', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <div id="jackpot-wheel-overlay" hidden>
                <svg viewBox="-110 -110 220 220">
                    <g class="jackpot-wheel-rotor"></g>
                </svg>
                <div class="jackpot-wheel-result"></div>
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
        // rotation-set step in `spinTo` actually fires under jsdom.
        window.requestAnimationFrame = cb => cb(0);
    });

    afterEach(() => {
        delete window.__jackpotWheel;
        delete window.TobyJackpot;
        delete window.requestAnimationFrame;
    });

    test('reveals the overlay and rotates the rotor to the target wedge', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        spinTo(4, 500, 50, () => {});
        expect(overlay.hidden).toBe(false);
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        // Final angle = 4 full rotations - target midpoint angle.
        // Tier 4 (the 1-weight 50% segment) midpoint is at ~358.2°.
        // Rotation should therefore be around 4*360 - 358.2 = 1081.8°.
        expect(rotor.style.transform).toMatch(/rotate\(108[0-9](\.\d+)?deg\)/);
    });

    test('paints the tier label on the result element when settled', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const resultEl = overlay.querySelector('.jackpot-wheel-result');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        spinTo(0, 12, 1, () => {});
        // Force the settle path manually — jsdom doesn't dispatch
        // transitionend on its own.
        rotor.dispatchEvent(new Event('transitionend'));
        expect(resultEl.textContent).toContain('Pity prize');
        expect(resultEl.textContent).toContain('+12 credits');
    });

    test('invokes onSettle exactly once on transitionend', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        const settled = jest.fn();
        spinTo(2, 100, 10, settled);
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

    test('holds the pool banner while spinning and releases on settle', () => {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        // Force-release any prior held state.
        jackpot.releasePoolBanner();
        spinTo(0, 50, 1, () => {});
        // Banner is held — an update queues, doesn't paint.
        jackpot.updatePoolBanner({ jackpotPool: 999 });
        const strong = document.querySelector('.casino-jackpot-banner strong');
        expect(strong.textContent).toBe('0');
        rotor.dispatchEvent(new Event('transitionend'));
        // After settle the queued value is flushed.
        expect(strong.textContent).toBe('999');
    });
});
