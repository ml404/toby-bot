// Pure-logic tests for the cube workshop page. The URL builders and DOM
// renderers carry the page's only real logic; pin them so the page can't
// silently start hitting the wrong endpoint or mis-rendering results.

const Cube = require('../../main/resources/static/js/cube');

describe('formatAsFan', () => {
    test('formats to two decimal places', () => {
        expect(Cube.formatAsFan(1.6667)).toBe('1.67');
        expect(Cube.formatAsFan(2)).toBe('2.00');
        expect(Cube.formatAsFan(0)).toBe('0.00');
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
    test('renders one row per category with the as-fan suffix', () => {
        const tbody = document.createElement('tbody');
        Cube.renderDistribution(tbody, [
            { category: 'White', count: 36, asFan: 1.0 },
            { category: 'Land', count: 18, asFan: 0.5 },
        ]);
        const rows = tbody.querySelectorAll('tr');
        expect(rows).toHaveLength(2);
        const firstCells = rows[0].querySelectorAll('td');
        expect(firstCells[0].textContent).toBe('White');
        expect(firstCells[1].textContent).toBe('36');
        expect(firstCells[2].textContent).toBe('1.00 / pack');
    });

    test('replaces any previous rows on re-render', () => {
        const tbody = document.createElement('tbody');
        Cube.renderDistribution(tbody, [{ category: 'Red', count: 1, asFan: 1 }]);
        Cube.renderDistribution(tbody, [{ category: 'Blue', count: 2, asFan: 2 }]);
        const rows = tbody.querySelectorAll('tr');
        expect(rows).toHaveLength(1);
        expect(rows[0].querySelector('td').textContent).toBe('Blue');
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
        expect(packs[0].querySelector('h3').textContent).toBe('Pack 1 (2)');
        expect(packs[0].querySelectorAll('li')).toHaveLength(2);
        expect(packs[1].querySelector('h3').textContent).toBe('Pack 2 (1)');
        expect(packs[1].querySelector('li').textContent).toBe('Forest');
    });
});
