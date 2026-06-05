// Pure-logic tests for the cube workshop page. The URL builders, tab/hash
// mapping, category colours, and DOM renderers carry the page's only real
// logic; pin them so the page can't silently hit the wrong endpoint, lose
// a tab deep-link, or mis-render the as-fan bars.

const Cube = require('../../main/resources/static/js/cube');

describe('formatAsFan', () => {
    test('formats to two decimal places', () => {
        expect(Cube.formatAsFan(1.6667)).toBe('1.67');
        expect(Cube.formatAsFan(2)).toBe('2.00');
        expect(Cube.formatAsFan(0)).toBe('0.00');
    });
});

describe('categoryColor', () => {
    test('maps every colour-pie bucket to a distinct swatch', () => {
        const names = ['White', 'Blue', 'Black', 'Red', 'Green', 'Multicolor', 'Colorless', 'Land'];
        const colors = names.map(Cube.categoryColor);
        expect(new Set(colors).size).toBe(names.length);
        colors.forEach((c) => expect(c).toMatch(/^#[0-9a-f]{6}$/i));
    });

    test('falls back to a neutral colour for an unknown category', () => {
        expect(Cube.categoryColor('Eldrazi')).toBe('#7a7a8a');
    });
});

describe('tabIdFromHash', () => {
    test('recognises the three tab hashes', () => {
        expect(Cube.tabIdFromHash('#asfan')).toBe('asfan');
        expect(Cube.tabIdFromHash('#preview')).toBe('preview');
        expect(Cube.tabIdFromHash('#generate')).toBe('generate');
    });

    test('returns null for anything else', () => {
        expect(Cube.tabIdFromHash('#nope')).toBeNull();
        expect(Cube.tabIdFromHash('')).toBeNull();
        expect(Cube.tabIdFromHash(null)).toBeNull();
    });
});

describe('URL builders', () => {
    test('asfanUrl carries the three calculator params', () => {
        expect(Cube.asfanUrl({ total: 60, cubeSize: 540, packSize: 15 }))
            .toBe('/cube/api/asfan?total=60&cubeSize=540&packSize=15');
    });

    test('previewUrl url-encodes the query', () => {
        expect(Cube.previewUrl({ query: 't:dragon c:r', packSize: 15 }))
            .toBe('/cube/api/preview?query=t%3Adragon+c%3Ar&packSize=15');
    });

    test('generateUrl encodes the boolean balanced flag', () => {
        expect(Cube.generateUrl({ query: 'set:vow', packs: 24, packSize: 15, balanced: true }))
            .toBe('/cube/api/generate?query=set%3Avow&packs=24&packSize=15&balanced=true');
        expect(Cube.generateUrl({ query: 'set:vow', packs: 8, packSize: 15, balanced: false }))
            .toBe('/cube/api/generate?query=set%3Avow&packs=8&packSize=15&balanced=false');
    });
});

describe('renderDistribution', () => {
    test('renders one colour-coded, length-scaled bar per category', () => {
        const container = document.createElement('div');
        Cube.renderDistribution(container, [
            { category: 'White', count: 36, asFan: 2.0 },
            { category: 'Land', count: 18, asFan: 0.5 },
        ]);
        const rows = container.querySelectorAll('.cube-bar-row');
        expect(rows).toHaveLength(2);

        const first = rows[0];
        expect(first.querySelector('.cube-bar-label').textContent).toBe('White');
        expect(first.querySelector('.cube-bar-value').textContent).toBe('2.00 / pack · 36');
        // Largest as-fan gets a full-width bar.
        expect(first.querySelector('.cube-bar-fill').style.width).toBe('100%');
        // Half the max → quarter… no: 0.5 of 2.0 = 25%.
        expect(rows[1].querySelector('.cube-bar-fill').style.width).toBe('25%');
    });

    test('replaces any previous bars on re-render', () => {
        const container = document.createElement('div');
        Cube.renderDistribution(container, [{ category: 'Red', count: 1, asFan: 1 }]);
        Cube.renderDistribution(container, [{ category: 'Blue', count: 2, asFan: 2 }]);
        const rows = container.querySelectorAll('.cube-bar-row');
        expect(rows).toHaveLength(1);
        expect(rows[0].querySelector('.cube-bar-label').textContent).toBe('Blue');
    });
});

describe('renderPacks', () => {
    test('renders a titled card with a card list per pack', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [
            ['Bolt', 'Shock'],
            ['Forest'],
        ]);
        const packs = container.querySelectorAll('.cube-pack');
        expect(packs).toHaveLength(2);
        expect(packs[0].querySelector('h3').textContent).toContain('Pack 1');
        expect(packs[0].querySelector('.cube-pack-count').textContent).toBe('2 cards');
        expect(packs[0].querySelectorAll('li')).toHaveLength(2);
        expect(packs[1].querySelector('li').textContent).toBe('Forest');
    });
});
