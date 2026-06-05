// Pure-logic tests for the cube workshop page. The URL builders, tab/hash
// mapping, category colours, card links, and DOM renderers carry the
// page's only real logic; pin them so the page can't silently hit the
// wrong endpoint, lose a tab deep-link, or stop showing the actual cards.

const Cube = require('../../main/resources/static/js/cube');

describe('formatAsFan', () => {
    test('formats to two decimal places', () => {
        expect(Cube.formatAsFan(1.6667)).toBe('1.67');
        expect(Cube.formatAsFan(2)).toBe('2.00');
        expect(Cube.formatAsFan(0)).toBe('0.00');
    });
});

describe('asfanSentence', () => {
    test('reads as a plain-English sentence', () => {
        expect(Cube.asfanSentence(1.6667, 15))
            .toBe("On average you'll open about 1.67 of these in a 15-card pack.");
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

describe('cardStatline', () => {
    test('joins type line and mana value', () => {
        expect(Cube.cardStatline('Instant', 1)).toBe('Instant · MV 1');
        expect(Cube.cardStatline('Creature — Goblin', 2)).toBe('Creature — Goblin · MV 2');
    });

    test('omits mana value for 0-cost cards like lands', () => {
        expect(Cube.cardStatline('Basic Land — Forest', 0)).toBe('Basic Land — Forest');
    });

    test('handles a missing type line', () => {
        expect(Cube.cardStatline('', 3)).toBe('MV 3');
    });
});

describe('scryfallCardUrl', () => {
    test('builds an exact-name Scryfall search link', () => {
        expect(Cube.scryfallCardUrl('Lightning Bolt'))
            .toBe('https://scryfall.com/search?q=' + encodeURIComponent('!"Lightning Bolt"'));
    });

    test('encodes punctuation in card names', () => {
        // Apostrophes / commas must survive into a valid URL.
        expect(Cube.scryfallCardUrl("Urza's Saga")).toContain(encodeURIComponent('!"Urza\'s Saga"'));
    });
});

describe('tabIdFromHash', () => {
    test('recognises the three tab hashes', () => {
        expect(Cube.tabIdFromHash('#generate')).toBe('generate');
        expect(Cube.tabIdFromHash('#preview')).toBe('preview');
        expect(Cube.tabIdFromHash('#asfan')).toBe('asfan');
    });

    test('returns null for anything else', () => {
        expect(Cube.tabIdFromHash('#nope')).toBeNull();
        expect(Cube.tabIdFromHash('')).toBeNull();
        expect(Cube.tabIdFromHash(null)).toBeNull();
    });
});

describe('packsToText', () => {
    test('renders each pack as a titled, indented block of card names', () => {
        const text = Cube.packsToText([
            [{ name: 'Bolt', imageUrl: 'u1' }, { name: 'Shock', imageUrl: null }],
            [{ name: 'Forest', imageUrl: 'u3' }],
        ]);
        expect(text).toContain('== Pack 1 (2 cards) ==');
        expect(text).toContain('  Bolt');
        expect(text).toContain('== Pack 2 (1 cards) ==');
        expect(text).toContain('  Forest');
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

describe('renderGroups (preview shows the actual cards as thumbnails)', () => {
    test('renders a group per category, each card a thumbnail tile linking to Scryfall', () => {
        const container = document.createElement('div');
        Cube.renderGroups(container, [
            {
                category: 'Red', count: 2, asFan: 2.0, cards: [
                    { name: 'Bolt', imageUrl: 'https://img/bolt.jpg', imageUrlLarge: 'https://img/bolt-lg.jpg', typeLine: 'Instant', manaValue: 1 },
                    { name: 'Shock', imageUrl: null, imageUrlLarge: null, typeLine: 'Instant', manaValue: 1 },
                ],
            },
            {
                category: 'Land', count: 1, asFan: 0.5,
                cards: [{ name: 'Wastes', imageUrl: 'https://img/wastes.jpg', imageUrlLarge: 'https://img/wastes-lg.jpg' }],
            },
        ]);
        const blocks = container.querySelectorAll('.cube-group');
        expect(blocks).toHaveLength(2);

        const tiles = blocks[0].querySelectorAll('.cube-card-grid .cube-card');
        expect(tiles).toHaveLength(2);
        // First card has an image + the large URL for hover-zoom.
        const img = tiles[0].querySelector('img.cube-card-img');
        expect(img.getAttribute('src')).toBe('https://img/bolt.jpg');
        expect(img.getAttribute('loading')).toBe('lazy');
        expect(tiles[0].getAttribute('href')).toBe(Cube.scryfallCardUrl('Bolt'));
        expect(tiles[0].getAttribute('data-large')).toBe('https://img/bolt-lg.jpg');
        expect(tiles[0].getAttribute('data-statline')).toBe('Instant · MV 1');
        expect(tiles[0].querySelector('.cube-card-name').textContent).toBe('Bolt');
        // Second card has no image → placeholder, no <img>, no zoom target.
        expect(tiles[1].querySelector('img')).toBeNull();
        expect(tiles[1].querySelector('.cube-card-img-empty')).not.toBeNull();
        expect(tiles[1].hasAttribute('data-large')).toBe(false);
        // Header still shows the as-fan.
        expect(blocks[0].querySelector('.cube-bar-value').textContent).toBe('2.00 / pack');
    });
});

describe('renderPacks', () => {
    test('renders each pack\'s cards as thumbnail tiles linking to Scryfall', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [
            [
                { name: 'Bolt', imageUrl: 'https://img/bolt.jpg', imageUrlLarge: 'https://img/bolt-lg.jpg' },
                { name: 'Shock', imageUrl: null, imageUrlLarge: null },
            ],
            [{ name: 'Forest', imageUrl: 'https://img/forest.jpg', imageUrlLarge: 'https://img/forest-lg.jpg' }],
        ]);
        const packs = container.querySelectorAll('.cube-pack');
        expect(packs).toHaveLength(2);
        expect(packs[0].querySelector('h3').textContent).toContain('Pack 1');
        expect(packs[0].querySelector('.cube-pack-count').textContent).toBe('2 cards');
        const tiles = packs[0].querySelectorAll('.cube-card-grid .cube-card');
        expect(tiles).toHaveLength(2);
        expect(tiles[0].querySelector('img.cube-card-img').getAttribute('src')).toBe('https://img/bolt.jpg');
        expect(tiles[0].getAttribute('href')).toBe(Cube.scryfallCardUrl('Bolt'));
        expect(tiles[0].getAttribute('data-large')).toBe('https://img/bolt-lg.jpg');
        expect(packs[1].querySelector('.cube-card-name').textContent).toBe('Forest');
    });
});

describe('zoomPosition', () => {
    const VW = 1000;
    const VH = 800;

    test('offsets the preview to the right of the cursor by default', () => {
        const pos = Cube.zoomPosition(100, 400, 300, 420, VW, VH);
        expect(pos.left).toBe(118); // 100 + 18 offset
    });

    test('flips to the left when the preview would overflow the right edge', () => {
        const pos = Cube.zoomPosition(900, 400, 300, 420, VW, VH);
        // 900 + 18 + 300 = 1218 > 1000 → flip: 900 - 18 - 300 = 582
        expect(pos.left).toBe(582);
    });

    test('clamps vertically within the viewport margin', () => {
        const top = Cube.zoomPosition(100, 10, 300, 420, VW, VH).top;
        expect(top).toBe(8); // MARGIN, not a negative off-screen value

        const bottom = Cube.zoomPosition(100, 790, 300, 420, VW, VH).top;
        expect(bottom).toBe(VH - 420 - 8); // pinned so the bottom stays on-screen
    });
});

describe('hover-to-enlarge', () => {
    test('hovering a card shows the large image + stat line; leaving hides it', () => {
        // cube.js wires the jsdom document on import, creating the overlay.
        const card = document.createElement('a');
        card.className = 'cube-card';
        card.setAttribute('data-large', 'https://img/big.jpg');
        card.setAttribute('data-statline', 'Instant · MV 1');
        document.body.appendChild(card);

        card.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, clientX: 40, clientY: 40 }));
        const overlay = document.querySelector('.cube-zoom');
        expect(overlay).not.toBeNull();
        expect(overlay.hidden).toBe(false);
        expect(overlay.querySelector('.cube-zoom-img').getAttribute('src')).toBe('https://img/big.jpg');
        expect(overlay.querySelector('.cube-zoom-stat').textContent).toBe('Instant · MV 1');

        card.dispatchEvent(new MouseEvent('mouseout', { bubbles: true }));
        expect(overlay.hidden).toBe(true);

        document.body.removeChild(card);
    });

    test('cards without a large image never trigger the overlay', () => {
        const card = document.createElement('a');
        card.className = 'cube-card'; // no data-large
        document.body.appendChild(card);

        card.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, clientX: 40, clientY: 40 }));
        expect(document.querySelector('.cube-zoom').hidden).toBe(true);

        document.body.removeChild(card);
    });
});

describe('renderDistribution (the secondary balance bars)', () => {
    test('renders one colour-coded, length-scaled bar per category', () => {
        const container = document.createElement('div');
        Cube.renderDistribution(container, [
            { category: 'White', count: 36, asFan: 2.0 },
            { category: 'Land', count: 18, asFan: 0.5 },
        ]);
        const rows = container.querySelectorAll('.cube-bar-row');
        expect(rows).toHaveLength(2);
        expect(rows[0].querySelector('.cube-bar-value').textContent).toBe('2.00 / pack · 36');
        expect(rows[0].querySelector('.cube-bar-fill').style.width).toBe('100%');
        expect(rows[1].querySelector('.cube-bar-fill').style.width).toBe('25%');
    });
});
