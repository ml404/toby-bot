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

describe('collapsible result sections', () => {
    test('packs render as open <details> with a summary, so they can be collapsed', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }]]);
        const pack = container.querySelector('.cube-pack');
        expect(pack.tagName).toBe('DETAILS');
        expect(pack.open).toBe(true);
        expect(pack.querySelector('summary')).not.toBeNull();
    });

    test('preview groups render as open <details>', () => {
        const container = document.createElement('div');
        Cube.renderGroups(container, [
            { category: 'Red', count: 1, asFan: 1, cards: [{ name: 'Bolt' }] },
            { category: 'Land', count: 1, asFan: 0.5, cards: [{ name: 'Wastes' }] },
        ]);
        const group = container.querySelector('.cube-group');
        expect(group.tagName).toBe('DETAILS');
        expect(group.open).toBe(true);
        // The header (with its as-fan) lives in the summary.
        expect(group.querySelector('summary .cube-bar-value').textContent).toBe('1.00 / pack');
    });

    test('a multi-section deal gets a Collapse all / Expand all toggle', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }], [{ name: 'Island' }]]);
        const btn = container.querySelector('[data-collapse-all]');
        expect(btn).not.toBeNull();
        expect(btn.textContent).toBe('Collapse all');

        btn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        let packs = container.querySelectorAll('.cube-pack');
        packs.forEach((d) => expect(d.open).toBe(false));
        expect(btn.textContent).toBe('Expand all');

        btn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        packs = container.querySelectorAll('.cube-pack');
        packs.forEach((d) => expect(d.open).toBe(true));
        expect(btn.textContent).toBe('Collapse all');
    });

    test('a single section gets no collapse-all toggle', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }]]);
        expect(container.querySelector('[data-collapse-all]')).toBeNull();
    });

    test('a single preview group also gets no collapse-all toggle', () => {
        const container = document.createElement('div');
        Cube.renderGroups(container, [
            { category: 'Red', count: 1, asFan: 1, cards: [{ name: 'Bolt' }] },
        ]);
        expect(container.querySelector('[data-collapse-all]')).toBeNull();
        expect(container.querySelectorAll('.cube-group')).toHaveLength(1);
    });

    test('each pack summary leads with an aria-hidden chevron', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }]]);
        const summary = container.querySelector('.cube-pack > summary');
        const chevron = summary.firstChild;
        expect(chevron.className).toBe('cube-collapse-chevron');
        expect(chevron.getAttribute('aria-hidden')).toBe('true');
    });

    test('each group summary leads with a chevron, then the as-fan header', () => {
        const container = document.createElement('div');
        Cube.renderGroups(container, [
            { category: 'Red', count: 2, asFan: 2, cards: [{ name: 'Bolt' }] },
            { category: 'Land', count: 1, asFan: 0.5, cards: [{ name: 'Wastes' }] },
        ]);
        const summary = container.querySelector('.cube-group > summary');
        expect(summary.firstChild.className).toBe('cube-collapse-chevron');
        expect(summary.querySelector('.cube-group-head .cube-bar-value').textContent).toBe('2.00 / pack');
    });

    test('the collapse-all bar sits above the sections', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }]]);
        expect(container.firstChild.className).toBe('cube-collapse-bar');
        expect(container.firstChild.querySelector('[data-collapse-all]')).not.toBeNull();
    });

    test("the card grid is a direct child of the <details>, so collapsing hides the cards", () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }]]);
        const pack = container.querySelector('.cube-pack');
        const grid = pack.querySelector('.cube-card-grid');
        expect(grid.parentElement).toBe(pack); // sibling of <summary>, inside <details>
        expect(grid.previousElementSibling.tagName).toBe('SUMMARY');
    });

    test('preview groups get their own working Collapse all toggle', () => {
        const container = document.createElement('div');
        Cube.renderGroups(container, [
            { category: 'Red', count: 1, asFan: 1, cards: [{ name: 'Bolt' }] },
            { category: 'Blue', count: 1, asFan: 1, cards: [{ name: 'Counterspell' }] },
        ]);
        const btn = container.querySelector('[data-collapse-all]');
        expect(btn).not.toBeNull();
        btn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        container.querySelectorAll('.cube-group').forEach((d) => expect(d.open).toBe(false));
        expect(btn.textContent).toBe('Expand all');
    });

    test('Collapse all closes everything even from a mixed open/closed state', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }], [{ name: 'Island' }]]);
        const packs = container.querySelectorAll('.cube-pack');
        packs[0].open = false; // one already collapsed, the rest open
        const btn = container.querySelector('[data-collapse-all]');

        btn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        // Any-open → collapse them all.
        container.querySelectorAll('.cube-pack').forEach((d) => expect(d.open).toBe(false));
        expect(btn.textContent).toBe('Expand all');

        btn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        // None open → expand them all.
        container.querySelectorAll('.cube-pack').forEach((d) => expect(d.open).toBe(true));
        expect(btn.textContent).toBe('Collapse all');
    });

    test('the collapse-all button is a styled link-button carrying the data hook', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }]]);
        const btn = container.querySelector('[data-collapse-all]');
        expect(btn.tagName).toBe('BUTTON');
        expect(btn.getAttribute('type')).toBe('button');
        expect(btn.className).toContain('cube-link-btn');
    });

    test('re-rendering replaces the sections and leaves exactly one toggle', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }], [{ name: 'Island' }]]);
        // Re-render with fewer packs (e.g. a new, smaller deal).
        Cube.renderPacks(container, [[{ name: 'Swamp' }], [{ name: 'Plains' }]]);
        expect(container.querySelectorAll('[data-collapse-all]')).toHaveLength(1);
        expect(container.querySelectorAll('.cube-pack')).toHaveLength(2);
        expect(container.querySelector('.cube-pack .cube-card-name').textContent).toBe('Swamp');
    });

    test('re-rendering down to a single section drops the toggle entirely', () => {
        const container = document.createElement('div');
        Cube.renderPacks(container, [[{ name: 'Bolt' }], [{ name: 'Forest' }]]);
        expect(container.querySelector('[data-collapse-all]')).not.toBeNull();
        Cube.renderPacks(container, [[{ name: 'Swamp' }]]);
        expect(container.querySelector('[data-collapse-all]')).toBeNull();
    });
});

describe('deep-link hash activates the matching tab on in-page navigation', () => {
    // wire() ran at require time and registered a hashchange listener on the
    // window; here we stand up the tab markup and fire a hashchange to prove a
    // /cube#preview deep-link (clicked while already on /cube) switches tabs.
    // Append to a throwaway container (not innerHTML on body) so the shared
    // zoom/lightbox overlays wire() created at require time survive.
    let host;
    afterEach(() => {
        window.location.hash = '';
        if (host && host.parentNode) host.parentNode.removeChild(host);
        host = null;
    });

    function setUpTabs() {
        host = document.createElement('div');
        host.innerHTML =
            '<section class="cube-source-card" data-needs-cube></section>' +
            '<button role="tab" data-tab="generate" aria-selected="true"></button>' +
            '<button role="tab" data-tab="preview" aria-selected="false"></button>' +
            '<button role="tab" data-tab="asfan" aria-selected="false"></button>' +
            '<section data-panel="generate"></section>' +
            '<section data-panel="preview" hidden></section>' +
            '<section data-panel="asfan" hidden></section>';
        document.body.appendChild(host);
    }

    test('a hashchange to #preview reveals the preview panel and selects its tab', () => {
        setUpTabs();
        window.location.hash = '#preview';
        window.dispatchEvent(new window.Event('hashchange'));

        expect(document.querySelector('[data-panel="preview"]').hidden).toBe(false);
        expect(document.querySelector('[data-panel="generate"]').hidden).toBe(true);
        expect(document.querySelector('[data-tab="preview"]').getAttribute('aria-selected')).toBe('true');
    });

    test('a hashchange to #asfan reveals the as-fan panel', () => {
        setUpTabs();
        window.location.hash = '#asfan';
        window.dispatchEvent(new window.Event('hashchange'));

        expect(document.querySelector('[data-panel="asfan"]').hidden).toBe(false);
        expect(document.querySelector('[data-tab="asfan"]').getAttribute('aria-selected')).toBe('true');
    });

    test('an unknown hash leaves the tabs untouched', () => {
        setUpTabs();
        window.location.hash = '#nonsense';
        window.dispatchEvent(new window.Event('hashchange'));

        expect(document.querySelector('[data-panel="generate"]').hidden).toBe(false);
        expect(document.querySelector('[data-panel="preview"]').hidden).toBe(true);
    });

    test('the as-fan tab hides the standalone "Your cube" source; other tabs show it', () => {
        setUpTabs();
        const source = document.querySelector('[data-needs-cube]');

        window.location.hash = '#asfan';
        window.dispatchEvent(new window.Event('hashchange'));
        expect(source.hidden).toBe(true);

        window.location.hash = '#preview';
        window.dispatchEvent(new window.Event('hashchange'));
        expect(source.hidden).toBe(false);
    });
});

describe('manaSymbolUrls', () => {
    test('maps each {sym} to a Scryfall symbol SVG, stripping braces and slashes', () => {
        const urls = Cube.manaSymbolUrls('{1}{W/U}{R}');
        expect(urls.map((u) => u.symbol)).toEqual(['{1}', '{W/U}', '{R}']);
        expect(urls[0].url).toBe('https://svgs.scryfall.io/card-symbols/1.svg');
        expect(urls[1].url).toBe('https://svgs.scryfall.io/card-symbols/WU.svg');
        expect(urls[2].url).toBe('https://svgs.scryfall.io/card-symbols/R.svg');
    });
    test('returns an empty list for a costless / null cost', () => {
        expect(Cube.manaSymbolUrls(null)).toEqual([]);
        expect(Cube.manaSymbolUrls('')).toEqual([]);
    });
});

describe('cardTile enrichments (via renderGroups)', () => {
    function tileFor(card) {
        const container = document.createElement('div');
        Cube.renderGroups(container, [{ category: 'Red', count: card.count || 1, asFan: 1.0, cards: [card] }]);
        return container.querySelector('.cube-card');
    }

    test('renders a mana-symbol row from the mana cost', () => {
        const tile = tileFor({ name: 'Bolt', imageUrl: 'i', imageUrlLarge: 'l', typeLine: 'Instant', manaValue: 1, manaCost: '{R}' });
        const syms = tile.querySelectorAll('.cube-card-mana .cube-mana-symbol');
        expect(syms).toHaveLength(1);
        expect(syms[0].getAttribute('src')).toBe('https://svgs.scryfall.io/card-symbols/R.svg');
    });

    test('shows a copy-count badge only when count > 1', () => {
        const many = tileFor({ name: 'Forest', imageUrl: 'i', imageUrlLarge: 'l', typeLine: 'Basic Land — Forest', manaValue: 0, count: 10 });
        expect(many.querySelector('.cube-card-qty').textContent).toBe('×10');
        const one = tileFor({ name: 'Sol Ring', imageUrl: 'i', imageUrlLarge: 'l', typeLine: 'Artifact', manaValue: 1, count: 1 });
        expect(one.querySelector('.cube-card-qty')).toBeNull();
    });

    test('a double-faced card gets a flip control and back-face data; a single-faced one does not', () => {
        const dfc = tileFor({ name: 'Huntmaster', imageUrl: 'front-sm', imageUrlLarge: 'front-lg', imageUrlBack: 'back', typeLine: 'Creature', manaValue: 3 });
        expect(dfc.getAttribute('data-back')).toBe('back');
        expect(dfc.querySelector('.cube-card-flip')).not.toBeNull();
        const plain = tileFor({ name: 'Bolt', imageUrl: 'i', imageUrlLarge: 'l', typeLine: 'Instant', manaValue: 1 });
        expect(plain.querySelector('.cube-card-flip')).toBeNull();
    });
});

describe('card flip wiring', () => {
    test('flipping swaps the thumbnail and the hover/lightbox image to the back face and back', () => {
        const container = document.createElement('div');
        document.body.appendChild(container);
        Cube.renderGroups(container, [{
            category: 'Red', count: 1, asFan: 1.0, cards: [
                { name: 'DFC', imageUrl: 'front-sm', imageUrlLarge: 'front-lg', imageUrlBack: 'back', typeLine: 'Creature', manaValue: 3 },
            ],
        }]);
        const tile = container.querySelector('.cube-card');
        const img = tile.querySelector('img.cube-card-img');
        const flip = tile.querySelector('.cube-card-flip');
        expect(tile.getAttribute('data-large')).toBe('front-lg');

        flip.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        expect(tile.getAttribute('data-large')).toBe('back');
        expect(img.getAttribute('src')).toBe('back');

        flip.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        expect(tile.getAttribute('data-large')).toBe('front-lg');
        expect(img.getAttribute('src')).toBe('front-sm');
        document.body.removeChild(container);
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

describe('deleteListUrl', () => {
    test('encodes the saved-list name into the delete query', () => {
        expect(Cube.deleteListUrl('My Cube')).toBe('/cube/api/lists?name=My%20Cube');
        expect(Cube.deleteListUrl('Pauper / Peasant')).toBe('/cube/api/lists?name=Pauper%20%2F%20Peasant');
    });
});

describe('absoluteUrl', () => {
    test('joins the page origin with the relative share path', () => {
        expect(Cube.absoluteUrl('https://toby-bot.co.uk', '/cube/c/abc123'))
            .toBe('https://toby-bot.co.uk/cube/c/abc123');
    });

    test('tolerates a missing origin', () => {
        expect(Cube.absoluteUrl(null, '/cube/c/abc123')).toBe('/cube/c/abc123');
    });
});

describe('queryShareUrl', () => {
    test('builds a ?q= deep link, encoding the query', () => {
        expect(Cube.queryShareUrl('https://toby-bot.co.uk', 't:dragon c:r'))
            .toBe('https://toby-bot.co.uk/cube?q=t%3Adragon%20c%3Ar');
    });
});

describe('readUrlPrefill', () => {
    test('reads a ?q= query param', () => {
        expect(Cube.readUrlPrefill('?q=set%3Avow')).toEqual({ q: 'set:vow' });
    });
    test('reads a ?list= param', () => {
        expect(Cube.readUrlPrefill('?list=Bolt%0AForest')).toEqual({ list: 'Bolt\nForest' });
    });
    test('is empty when neither is present', () => {
        expect(Cube.readUrlPrefill('?foo=bar')).toEqual({});
        expect(Cube.readUrlPrefill('')).toEqual({});
    });
});

describe('countCards', () => {
    test('counts non-blank, non-comment lines', () => {
        expect(Cube.countCards('Bolt\nForest\n\n# comment\n// also\n3 Island')).toBe(3);
    });
    test('is zero for empty or comment-only text', () => {
        expect(Cube.countCards('')).toBe(0);
        expect(Cube.countCards('# just a note\n\n')).toBe(0);
    });
});

describe('card-name autocomplete helpers', () => {
    test('scryfallAutocompleteUrl encodes the partial query', () => {
        expect(Cube.scryfallAutocompleteUrl('lightn')).toBe('https://api.scryfall.com/cards/autocomplete?q=lightn');
        expect(Cube.scryfallAutocompleteUrl('sol r')).toBe('https://api.scryfall.com/cards/autocomplete?q=sol%20r');
    });

    test('currentLineInfo finds the line the caret sits on', () => {
        const value = 'Bolt\n3 ForE\nSol Ring';
        // caret inside the middle line ("3 ForE", positions 5..11)
        const info = Cube.currentLineInfo(value, 9);
        expect(info.text).toBe('3 ForE');
        expect(value.slice(info.start, info.end)).toBe('3 ForE');
    });

    test('splitQuantityPrefix peels a leading count off the card name', () => {
        expect(Cube.splitQuantityPrefix('3 Forest')).toEqual({ prefix: '3 ', name: 'Forest' });
        expect(Cube.splitQuantityPrefix('10x Island')).toEqual({ prefix: '10x ', name: 'Island' });
        expect(Cube.splitQuantityPrefix('Sol Ring')).toEqual({ prefix: '', name: 'Sol Ring' });
    });

    test('applyCardChoice completes the caret line, keeping the quantity', () => {
        const value = 'Bolt\n3 ForE\nSol Ring';
        const result = Cube.applyCardChoice(value, 9, 'Forest');
        expect(result.value).toBe('Bolt\n3 Forest\nSol Ring');
        // caret lands at the end of the completed line ("...3 Forest")
        expect(result.value.slice(0, result.caret)).toBe('Bolt\n3 Forest');
    });

    test('applyCardChoice works on the only line with no quantity', () => {
        const result = Cube.applyCardChoice('light', 5, 'Lightning Bolt');
        expect(result.value).toBe('Lightning Bolt');
    });
});

describe('tap-to-enlarge (touch / no-hover devices)', () => {
    const realMatchMedia = window.matchMedia;
    afterEach(() => { window.matchMedia = realMatchMedia; });

    function fakeHoverNone(matches) {
        window.matchMedia = (query) => ({ matches: query === '(hover: none)' ? matches : false, media: query });
    }

    test('a tap opens the lightbox with the image, stat line and Scryfall link; Escape closes it', () => {
        fakeHoverNone(true);
        const card = document.createElement('a');
        card.className = 'cube-card';
        card.setAttribute('data-large', 'https://img/big.jpg');
        card.setAttribute('data-statline', 'Instant · MV 1');
        card.href = 'https://scryfall.com/x';
        document.body.appendChild(card);

        card.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        const modal = document.querySelector('.cube-lightbox');
        expect(modal.hidden).toBe(false);
        expect(modal.querySelector('.cube-lightbox-img').getAttribute('src')).toBe('https://img/big.jpg');
        expect(modal.querySelector('.cube-lightbox-stat').textContent).toBe('Instant · MV 1');
        expect(modal.querySelector('.cube-lightbox-link').getAttribute('href')).toBe('https://scryfall.com/x');

        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        expect(modal.hidden).toBe(true);

        document.body.removeChild(card);
    });

    test('on a hover (desktop) device a card click is left alone — no lightbox', () => {
        fakeHoverNone(false);
        const card = document.createElement('a');
        card.className = 'cube-card';
        card.setAttribute('data-large', 'https://img/big.jpg');
        card.href = '#stay'; // hash change avoids jsdom navigation noise
        document.body.appendChild(card);

        card.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        expect(document.querySelector('.cube-lightbox').hidden).toBe(true);

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
