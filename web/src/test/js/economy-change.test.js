// Proves the market-page % indicator tracks the active chart window
// instead of being stuck on a 24h delta. The browser code reads the
// label from the active button's text content; here we pass the label
// in directly to keep the helper assertions pure.

const TobyEconomyChange = require('../../main/resources/static/js/economy-change');

describe('computeChangePct', () => {
    test('returns null when fewer than two points are supplied', () => {
        expect(TobyEconomyChange.computeChangePct([])).toBeNull();
        expect(TobyEconomyChange.computeChangePct([{ t: 1, price: 10 }])).toBeNull();
        expect(TobyEconomyChange.computeChangePct(null)).toBeNull();
        expect(TobyEconomyChange.computeChangePct(undefined)).toBeNull();
    });

    test('returns null when the anchor price is zero (avoids /0)', () => {
        const points = [{ t: 1, price: 0 }, { t: 2, price: 5 }];
        expect(TobyEconomyChange.computeChangePct(points)).toBeNull();
    });

    test('uses first and last price regardless of intermediate noise', () => {
        const points = [
            { t: 1, price: 100 },
            { t: 2, price: 999 },  // noise in the middle should not move the result
            { t: 3, price: 50 },
            { t: 4, price: 110 },
        ];
        expect(TobyEconomyChange.computeChangePct(points)).toBeCloseTo(10, 5);
    });

    test('returns a negative pct when the window ends below where it started', () => {
        const points = [{ t: 1, price: 200 }, { t: 2, price: 150 }];
        expect(TobyEconomyChange.computeChangePct(points)).toBeCloseTo(-25, 5);
    });
});

describe('formatChangeText', () => {
    test('prefixes positive deltas with + and tags the active window', () => {
        expect(TobyEconomyChange.formatChangeText(12.345, '1D')).toBe('+12.35% (1D)');
    });

    test('keeps the leading minus sign on negative deltas', () => {
        expect(TobyEconomyChange.formatChangeText(-3.2, '1Y')).toBe('-3.20% (1Y)');
    });

    test('renders zero as +0.00% rather than -0.00%', () => {
        expect(TobyEconomyChange.formatChangeText(0, 'ALL')).toBe('+0.00% (ALL)');
    });

    test('falls back to "no data yet" for null / undefined / NaN', () => {
        expect(TobyEconomyChange.formatChangeText(null, '1D')).toBe('no data yet');
        expect(TobyEconomyChange.formatChangeText(undefined, '1D')).toBe('no data yet');
        expect(TobyEconomyChange.formatChangeText(Number.NaN, '1D')).toBe('no data yet');
    });
});

describe('applyChange', () => {
    function makeEl() {
        const el = document.createElement('div');
        el.className = 'economy-price-change';
        return el;
    }

    test('marks an up move with the .up class and a + signed label', () => {
        const el = makeEl();
        TobyEconomyChange.applyChange(el, [
            { t: 1, price: 10 }, { t: 2, price: 12 }
        ], '5D');
        expect(el.classList.contains('up')).toBe(true);
        expect(el.classList.contains('down')).toBe(false);
        expect(el.textContent).toBe('+20.00% (5D)');
    });

    test('marks a down move with the .down class and a negative label', () => {
        const el = makeEl();
        TobyEconomyChange.applyChange(el, [
            { t: 1, price: 10 }, { t: 2, price: 8 }
        ], '1M');
        expect(el.classList.contains('down')).toBe(true);
        expect(el.classList.contains('up')).toBe(false);
        expect(el.textContent).toBe('-20.00% (1M)');
    });

    test('clears a stale up/down class when later applied to a sparse window', () => {
        const el = makeEl();
        el.classList.add('up'); // simulate a previous render
        TobyEconomyChange.applyChange(el, [{ t: 1, price: 10 }], '1Y');
        expect(el.classList.contains('up')).toBe(false);
        expect(el.classList.contains('down')).toBe(false);
        expect(el.textContent).toBe('no data yet');
    });

    test('flips classes when the same element is re-applied with a different window', () => {
        const el = makeEl();
        TobyEconomyChange.applyChange(el, [
            { t: 1, price: 10 }, { t: 2, price: 12 }
        ], '1D');
        expect(el.classList.contains('up')).toBe(true);
        expect(el.textContent).toBe('+20.00% (1D)');

        // Switching to a longer window where the trend is reversed should
        // re-render both class and label cleanly — no leftover .up.
        TobyEconomyChange.applyChange(el, [
            { t: 1, price: 50 }, { t: 2, price: 30 }
        ], 'ALL');
        expect(el.classList.contains('up')).toBe(false);
        expect(el.classList.contains('down')).toBe(true);
        expect(el.textContent).toBe('-40.00% (ALL)');
    });

    test('is a no-op when the element is missing', () => {
        expect(() => TobyEconomyChange.applyChange(null, [], '1D')).not.toThrow();
    });
});
