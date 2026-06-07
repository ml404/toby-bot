// Pure-logic tests for the cube workshop page. The URL builders, tab/hash
// mapping, category colours, card links, and DOM renderers carry the
// page's only real logic; pin them so the page can't silently hit the
// wrong endpoint, lose a tab deep-link, or stop showing the actual cards.

const Cube = require('../../main/resources/static/js/magic');

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
    test('recognises the tab hashes', () => {
        expect(Cube.tabIdFromHash('#generate')).toBe('generate');
        expect(Cube.tabIdFromHash('#preview')).toBe('preview');
        expect(Cube.tabIdFromHash('#asfan')).toBe('asfan');
        expect(Cube.tabIdFromHash('#compare')).toBe('compare');
        expect(Cube.tabIdFromHash('#card')).toBe('card');
        expect(Cube.tabIdFromHash('#legality')).toBe('legality');
        expect(Cube.tabIdFromHash('#reference')).toBe('reference');
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
            .toBe('/magic/api/asfan?total=60&cubeSize=540&packSize=15');
    });

    test('previewUrl url-encodes the query', () => {
        expect(Cube.previewUrl({ query: 't:dragon c:r', packSize: 15 }))
            .toBe('/magic/api/preview?query=t%3Adragon+c%3Ar&packSize=15');
    });

    test('generateUrl encodes the boolean balanced flag', () => {
        expect(Cube.generateUrl({ query: 'set:vow', packs: 24, packSize: 15, balanced: true }))
            .toBe('/magic/api/generate?query=set%3Avow&packs=24&packSize=15&balanced=true');
        expect(Cube.generateUrl({ query: 'set:vow', packs: 8, packSize: 15, balanced: false }))
            .toBe('/magic/api/generate?query=set%3Avow&packs=8&packSize=15&balanced=false');
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
    // /magic#preview deep-link (clicked while already on /magic) switches tabs.
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

describe('card lookup (cardUrl / renderCardLookup)', () => {
    test('cardUrl encodes the name', () => {
        expect(Cube.cardUrl('Urza, Lord High Artificer')).toBe('/magic/api/card?name=' + encodeURIComponent('Urza, Lord High Artificer'));
    });

    test('renderCardLookup shows the large image, facts, mana symbols and a Scryfall link', () => {
        const container = document.createElement('div');
        Cube.renderCardLookup(container, {
            name: 'Ragavan, Nimble Pilferer',
            imageUrl: 's.jpg', imageUrlLarge: 'n.jpg',
            typeLine: 'Legendary Creature — Monkey Pirate',
            manaValue: 1, manaCost: '{R}', rarity: 'Mythic', colors: ['Red'],
            oracleText: 'Whenever Ragavan deals combat damage, create a Treasure.',
        });
        expect(container.querySelector('.cube-cardlookup-img').getAttribute('src')).toBe('n.jpg');
        expect(container.querySelector('h3').textContent).toBe('Ragavan, Nimble Pilferer');
        const text = container.querySelector('.cube-cardlookup-facts').textContent;
        expect(text).toContain('Legendary Creature — Monkey Pirate');
        expect(text).toContain('Mythic');
        expect(text).toContain('Red');
        expect(container.querySelector('.cube-cardlookup-oracle').textContent).toContain('create a Treasure');
        // The mana cost renders as a Scryfall symbol image.
        expect(container.querySelector('.cube-mana-symbol').getAttribute('src')).toBe('https://svgs.scryfall.io/card-symbols/R.svg');
        expect(container.querySelector('.cube-cardlookup-link').getAttribute('href')).toBe(Cube.scryfallCardUrl('Ragavan, Nimble Pilferer'));
    });

    test('renderCardLookup describes a colourless card', () => {
        const container = document.createElement('div');
        Cube.renderCardLookup(container, { name: 'Sol Ring', imageUrlLarge: 'n.jpg', typeLine: 'Artifact', manaValue: 1, manaCost: '{1}', rarity: null, colors: [] });
        expect(container.querySelector('.cube-cardlookup-facts').textContent).toContain('Colourless');
    });

    test('renderCardLookup shows price and legal formats when present', () => {
        const container = document.createElement('div');
        Cube.renderCardLookup(container, {
            name: 'Ragavan, Nimble Pilferer', imageUrlLarge: 'n.jpg', typeLine: 'Legendary Creature',
            manaValue: 1, manaCost: '{R}', rarity: 'Mythic', colors: ['Red'],
            priceUsd: '60.00', priceEur: '55.50', priceTix: '12.00',
            legalFormats: ['Modern', 'Legacy'],
        });
        const text = container.querySelector('.cube-cardlookup-facts').textContent;
        expect(text).toContain('$60.00 · €55.50 · 12.00 tix');
        expect(text).toContain('Modern, Legacy');
    });

    test('renderCardLookup omits price and legal lines when the card has neither', () => {
        const container = document.createElement('div');
        Cube.renderCardLookup(container, { name: 'Token', imageUrlLarge: 'n.jpg', typeLine: 'Token', manaValue: 0, rarity: null, colors: [] });
        const text = container.querySelector('.cube-cardlookup-facts').textContent;
        expect(text).not.toContain('Price');
        expect(text).not.toContain('Legal');
    });

    test('renderCardLookup includes a Show rulings button carrying the card name, and a hidden box', () => {
        const container = document.createElement('div');
        Cube.renderCardLookup(container, { name: 'Doubling Season', imageUrlLarge: 'n.jpg', typeLine: 'Enchantment', manaValue: 5, rarity: 'Rare', colors: ['Green'] });
        const btn = container.querySelector('[data-load-rulings]');
        expect(btn).not.toBeNull();
        expect(btn.getAttribute('data-card-name')).toBe('Doubling Season');
        const box = container.querySelector('[data-rulings-result]');
        expect(box).not.toBeNull();
        expect(box.hidden).toBe(true);
    });

    test('renderCardLookup includes a Show combos button and a hidden combos box', () => {
        const container = document.createElement('div');
        Cube.renderCardLookup(container, { name: 'Kiki-Jiki', imageUrlLarge: 'n.jpg', typeLine: 'Creature', manaValue: 5, rarity: 'Rare', colors: ['Red'] });
        const btn = container.querySelector('[data-load-combos]');
        expect(btn).not.toBeNull();
        expect(btn.getAttribute('data-card-name')).toBe('Kiki-Jiki');
        const box = container.querySelector('[data-combos-result]');
        expect(box).not.toBeNull();
        expect(box.hidden).toBe(true);
    });
});

describe('combos (combosUrl / renderCombos)', () => {
    test('combosUrl encodes the name', () => {
        expect(Cube.combosUrl("Thassa's Oracle"))
            .toBe('/magic/api/combos?name=' + encodeURIComponent("Thassa's Oracle"));
    });

    test('renderCombos lists each combo with pieces, payoff and a link', () => {
        const container = document.createElement('div');
        Cube.renderCombos(container, {
            combos: [
                { id: '7', uses: ['Kiki-Jiki', 'Zealous Conscripts'], produces: ['Infinite haste creatures'], url: 'https://commanderspellbook.com/combo/7/' },
            ],
        });
        expect(container.querySelector('.cube-combos-h').textContent).toBe('Combos');
        const items = container.querySelectorAll('.cube-combo');
        expect(items).toHaveLength(1);
        expect(items[0].querySelector('.cube-combo-uses').textContent).toContain('Zealous Conscripts');
        expect(items[0].querySelector('.cube-combo-produces').textContent).toContain('Infinite haste creatures');
        expect(items[0].querySelector('.cube-combo-link').getAttribute('href')).toBe('https://commanderspellbook.com/combo/7/');
    });

    test('renderCombos shows an empty state when there are none', () => {
        const container = document.createElement('div');
        Cube.renderCombos(container, { combos: [] });
        expect(container.querySelector('.cube-combos-empty').textContent).toContain('No combos found');
        expect(container.querySelectorAll('.cube-combo')).toHaveLength(0);
    });
});

describe('rulings (rulingsUrl / renderRulings)', () => {
    test('rulingsUrl encodes the name', () => {
        expect(Cube.rulingsUrl('Urza, Lord High Artificer'))
            .toBe('/magic/api/rulings?name=' + encodeURIComponent('Urza, Lord High Artificer'));
    });

    test('renderRulings lists each ruling with its date', () => {
        const container = document.createElement('div');
        Cube.renderRulings(container, {
            rulings: [
                { publishedAt: '2021-03-19', comment: 'Tokens are doubled.' },
                { publishedAt: '2022-01-01', comment: 'Counters too.' },
            ],
        });
        expect(container.querySelector('.cube-rulings-h').textContent).toBe('Rulings');
        const items = container.querySelectorAll('.cube-ruling');
        expect(items).toHaveLength(2);
        expect(items[0].querySelector('.cube-ruling-date').textContent).toBe('2021-03-19');
        expect(items[0].querySelector('.cube-ruling-text').textContent).toBe('Tokens are doubled.');
    });

    test('renderRulings shows an empty state when there are none', () => {
        const container = document.createElement('div');
        Cube.renderRulings(container, { rulings: [] });
        expect(container.querySelector('.cube-rulings-empty').textContent).toContain('No official rulings');
        expect(container.querySelectorAll('.cube-ruling')).toHaveLength(0);
    });
});

describe('reference (set + rule lookup)', () => {
    test('setUrl and ruleUrl encode their query', () => {
        expect(Cube.setUrl('vow')).toBe('/magic/api/set?code=vow');
        expect(Cube.ruleUrl('double strike')).toBe('/magic/api/rule?term=' + encodeURIComponent('double strike'));
    });

    test('renderSet shows the headline facts and a Scryfall link', () => {
        const el = document.createElement('div');
        Cube.renderSet(el, {
            code: 'VOW', name: 'Innistrad: Crimson Vow', setType: 'expansion',
            releasedAt: '2021-11-19', cardCount: 277, scryfallUri: 'https://scryfall.com/sets/vow',
        });
        expect(el.querySelector('.cube-setinfo-h').textContent).toBe('Innistrad: Crimson Vow (VOW)');
        const text = el.textContent;
        expect(text).toContain('expansion');
        expect(text).toContain('2021-11-19');
        expect(text).toContain('277');
        expect(el.querySelector('a').getAttribute('href')).toBe('https://scryfall.com/sets/vow');
    });

    test('renderRule shows the keyword and reminder text', () => {
        const el = document.createElement('div');
        Cube.renderRule(el, { keyword: 'Trample', text: 'Excess combat damage tramples over.' });
        expect(el.querySelector('.cube-rule-h').textContent).toBe('Trample');
        expect(el.querySelector('.cube-rule-text').textContent).toContain('tramples over');
    });
});

describe('price watches (watchLine / renderWatches)', () => {
    test('watchLine formats a watch with its currency', () => {
        expect(Cube.watchLine({ cardName: 'Ragavan', currency: 'usd', direction: 'below', threshold: 30 }))
            .toBe('Ragavan — below $30.00 (USD)');
        expect(Cube.watchLine({ cardName: 'Mox', currency: 'eur', direction: 'above', threshold: 100 }))
            .toBe('Mox — above €100.00 (EUR)');
    });

    test('renderWatches lists watches with a wired Remove button', () => {
        const el = document.createElement('div');
        const removed = [];
        Cube.renderWatches(el, [
            { id: 1, cardName: 'Ragavan', currency: 'usd', direction: 'below', threshold: 30 },
            { id: 2, cardName: 'Mox', currency: 'usd', direction: 'above', threshold: 100 },
        ], function (id) { removed.push(id); });
        const items = el.querySelectorAll('.cube-watch');
        expect(items).toHaveLength(2);
        expect(items[0].querySelector('.cube-watch-text').textContent).toContain('Ragavan — below $30.00');
        items[0].querySelector('.cube-watch-remove').click();
        expect(removed).toEqual([1]);
    });

    test('renderWatches shows an empty state', () => {
        const el = document.createElement('div');
        Cube.renderWatches(el, [], function () {});
        expect(el.querySelector('.cube-watches-empty').textContent).toContain('No price watches');
    });
});

describe('priceLine (pure)', () => {
    test('joins present currencies only', () => {
        expect(Cube.priceLine({ priceUsd: '1.50' })).toBe('$1.50');
        expect(Cube.priceLine({ priceUsd: '1.50', priceEur: '1.20', priceTix: '0.03' })).toBe('$1.50 · €1.20 · 0.03 tix');
        expect(Cube.priceLine({ priceEur: '1.20' })).toBe('€1.20');
    });

    test('is empty when the card has no prices', () => {
        expect(Cube.priceLine({})).toBe('');
    });
});

describe('totalValueText (pure, per currency)', () => {
    const totals = [
        { currency: 'usd', display: 'USD', amount: 123.456 },
        { currency: 'eur', display: 'EUR', amount: 100 },
        { currency: 'tix', display: 'Tix', amount: 4.2 },
    ];

    test('formats the chosen currency with its symbol/suffix', () => {
        expect(Cube.totalValueText(totals, 'usd')).toBe('≈ $123.46 in priced cards (USD)');
        expect(Cube.totalValueText(totals, 'eur')).toBe('≈ €100.00 in priced cards (EUR)');
        expect(Cube.totalValueText(totals, 'tix')).toBe('≈ 4.20 tix in priced cards (Tix)');
    });

    test('is empty when the chosen currency has no total', () => {
        expect(Cube.totalValueText([{ currency: 'usd', display: 'USD', amount: 1 }], 'eur')).toBe('');
        expect(Cube.totalValueText([], 'usd')).toBe('');
    });
});

describe('renderTotalValue', () => {
    const totals = [{ currency: 'usd', display: 'USD', amount: 123.456 }, { currency: 'eur', display: 'EUR', amount: 99 }];

    test('shows the rounded total for the chosen currency and reveals the element', () => {
        const el = document.createElement('p');
        el.hidden = true;
        Cube.renderTotalValue(el, totals, 'usd');
        expect(el.hidden).toBe(false);
        expect(el.textContent).toBe('≈ $123.46 in priced cards (USD)');

        Cube.renderTotalValue(el, totals, 'eur');
        expect(el.textContent).toBe('≈ €99.00 in priced cards (EUR)');
    });

    test('hides the element when the chosen currency is unpriced or totals are empty', () => {
        const el = document.createElement('p');
        Cube.renderTotalValue(el, totals, 'tix'); // not in the totals
        expect(el.hidden).toBe(true);
        expect(el.textContent).toBe('');

        Cube.renderTotalValue(el, [], 'usd');
        expect(el.hidden).toBe(true);
    });
});

describe('formatMoney (pure)', () => {
    test('applies each currency symbol/suffix', () => {
        expect(Cube.formatMoney(1.5, 'usd')).toBe('$1.50');
        expect(Cube.formatMoney(1.5, 'eur')).toBe('€1.50');
        expect(Cube.formatMoney(1.5, 'tix')).toBe('1.50 tix');
    });
});

describe('renderValueExtremes', () => {
    const extremes = [
        { currency: 'usd', display: 'USD', mostName: 'Pricey', mostAmount: 60, leastName: 'Cheap', leastAmount: 0.25 },
        { currency: 'eur', display: 'EUR', mostName: 'Pricey', mostAmount: 55, leastName: 'Cheap', leastAmount: 0.2 },
    ];

    test('renders most and least valuable rows for the chosen currency', () => {
        const el = document.createElement('div');
        el.hidden = true;
        Cube.renderValueExtremes(el, extremes, 'usd');
        expect(el.hidden).toBe(false);
        const rows = el.querySelectorAll('.cube-extreme');
        expect(rows).toHaveLength(2);
        expect(rows[0].textContent).toContain('Pricey ($60.00)');
        expect(rows[1].textContent).toContain('Cheap ($0.25)');

        Cube.renderValueExtremes(el, extremes, 'eur');
        expect(el.querySelector('.cube-extreme-card').textContent).toContain('Pricey (€55.00)');
    });

    test('hides when the chosen currency has no extremes', () => {
        const el = document.createElement('div');
        Cube.renderValueExtremes(el, extremes, 'tix');
        expect(el.hidden).toBe(true);
        expect(el.querySelectorAll('.cube-extreme')).toHaveLength(0);

        Cube.renderValueExtremes(el, [], 'usd');
        expect(el.hidden).toBe(true);
    });
});

describe('renderDiff (compare two lists)', () => {
    test('renders Added / Removed / Count-changed sections with prefixes', () => {
        const container = document.createElement('div');
        Cube.renderDiff(container, {
            added: [{ name: 'Shock', from: 0, to: 1 }],
            removed: [{ name: 'Counterspell', from: 1, to: 0 }],
            changed: [{ name: 'Forest', from: 3, to: 5 }],
            sizeA: 5, sizeB: 7,
        });
        expect(container.querySelectorAll('.cube-diff-group')).toHaveLength(3);
        expect(container.querySelector('.cube-diff-add').textContent).toBe('+ Shock');
        expect(container.querySelector('.cube-diff-remove').textContent).toBe('− Counterspell');
        expect(container.querySelector('.cube-diff-change').textContent).toBe('~ Forest (3 → 5)');
    });

    test('omits empty sections and notes when the lists are identical', () => {
        const onlyAdds = document.createElement('div');
        Cube.renderDiff(onlyAdds, { added: [{ name: 'A', from: 0, to: 1 }], removed: [], changed: [], sizeA: 0, sizeB: 1 });
        expect(onlyAdds.querySelectorAll('.cube-diff-group')).toHaveLength(1);

        const same = document.createElement('div');
        Cube.renderDiff(same, { added: [], removed: [], changed: [], sizeA: 1, sizeB: 1 });
        expect(same.querySelector('.cube-diff-empty').textContent).toContain('No differences');
    });
});

describe('renderLegality (deck legality verdict)', () => {
    test('illegal deck shows the headline and bucketed offenders', () => {
        const el = document.createElement('div');
        Cube.renderLegality(el, {
            format: 'Modern', legal: false, total: 60,
            banned: ['Lurrus of the Dream-Den'], notLegal: ['Black Lotus'], restricted: [], unknown: [],
        });
        expect(el.querySelector('.cube-legality-headline').classList.contains('is-illegal')).toBe(true);
        expect(el.querySelector('.cube-legality-headline').textContent).toContain('Not legal in Modern');
        expect(el.querySelector('.cube-legality-banned').textContent).toContain('Lurrus of the Dream-Den');
        expect(el.querySelector('.cube-legality-notlegal').textContent).toContain('Black Lotus');
        expect(el.querySelector('.cube-legality-empty')).toBeNull();
    });

    test('legal deck shows a positive headline and the all-clear note', () => {
        const el = document.createElement('div');
        Cube.renderLegality(el, {
            format: 'Modern', legal: true, total: 60,
            banned: [], notLegal: [], restricted: [], unknown: [],
        });
        expect(el.querySelector('.cube-legality-headline').classList.contains('is-legal')).toBe(true);
        expect(el.querySelector('.cube-legality-empty').textContent).toContain('Every card is legal in Modern');
    });

    test('restricted cards are flagged without an all-clear note', () => {
        const el = document.createElement('div');
        Cube.renderLegality(el, {
            format: 'Vintage', legal: true, total: 60,
            banned: [], notLegal: [], restricted: ['Sol Ring'], unknown: [],
        });
        expect(el.querySelector('.cube-legality-restricted').textContent).toContain('Sol Ring');
        expect(el.querySelector('.cube-legality-empty')).toBeNull();
    });
});

describe('samplePackRequest (open a sample pack from the preview source)', () => {
    test('a Scryfall search becomes a GET to generate with packs=1', () => {
        const req = Cube.samplePackRequest({ mode: 'search', query: 'cube:vintage' }, '15');
        expect(req.method).toBe('GET');
        expect(req.url).toBe(Cube.generateUrl({ query: 'cube:vintage', packs: 1, packSize: 15, balanced: true }));
        expect(req.url).toContain('packs=1');
    });

    test('a pasted list becomes a POST body with packs=1', () => {
        const req = Cube.samplePackRequest({ mode: 'list', list: '40 Bolt' }, '20');
        expect(req.method).toBe('POST');
        expect(req.url).toBe('/magic/api/generate');
        expect(req.body).toEqual({ list: '40 Bolt', packs: 1, packSize: 20, balanced: true });
    });

    test('a missing/invalid pack size falls back to 15', () => {
        expect(Cube.samplePackRequest({ mode: 'list', list: 'x' }, '').body.packSize).toBe(15);
        expect(Cube.samplePackRequest({ mode: 'list', list: 'x' }, undefined).body.packSize).toBe(15);
    });
});

describe('cube list export (groupsToList / cubeListText)', () => {
    const groups = [
        { category: 'Red', count: 2, asFan: 2, cards: [{ name: 'Lightning Bolt', count: 1 }, { name: 'Shock', count: 1 }] },
        { category: 'Land', count: 10, asFan: 5, cards: [{ name: 'Forest', count: 10 }] },
    ];

    test('groupsToList emits one "<count> <name>" line per de-duped card', () => {
        expect(Cube.groupsToList(groups)).toEqual(['1 Lightning Bolt', '1 Shock', '10 Forest']);
    });

    test('groupsToList defaults a missing/oneish count to 1 and tolerates empties', () => {
        expect(Cube.groupsToList([{ cards: [{ name: 'Sol Ring' }] }])).toEqual(['1 Sol Ring']);
        expect(Cube.groupsToList([])).toEqual([]);
        expect(Cube.groupsToList(null)).toEqual([]);
        expect(Cube.groupsToList([{ category: 'Empty' }])).toEqual([]);
    });

    test('cubeListText joins the lines with a trailing newline (re-importable by the parser)', () => {
        expect(Cube.cubeListText(groups)).toBe('1 Lightning Bolt\n1 Shock\n10 Forest\n');
    });
});

describe('cube report analytics renderers', () => {
    test('renderManaCurve draws one scaled bar per bucket', () => {
        const container = document.createElement('div');
        Cube.renderManaCurve(container, [
            { label: '0', count: 0 },
            { label: '1', count: 10 },
            { label: '2', count: 5 },
            { label: '7+', count: 1 },
        ]);
        const rows = container.querySelectorAll('.cube-bar-row');
        expect(rows).toHaveLength(4);
        expect(rows[1].querySelector('.cube-bar-label').textContent).toBe('1');
        expect(rows[1].querySelector('.cube-bar-value').textContent).toBe('10');
        // Tallest bucket fills 100%, the empty one 0%.
        expect(rows[1].querySelector('.cube-bar-fill').style.width).toBe('100%');
        expect(rows[0].querySelector('.cube-bar-fill').style.width).toBe('0%');
        expect(rows[3].querySelector('.cube-bar-label').textContent).toBe('7+');
    });

    test('renderTypeBreakdown shows the type, as-fan and count', () => {
        const container = document.createElement('div');
        Cube.renderTypeBreakdown(container, [
            { type: 'Creature', count: 40, asFan: 4.2 },
            { type: 'Instant', count: 12, asFan: 1.26 },
        ]);
        const rows = container.querySelectorAll('.cube-bar-row');
        expect(rows[0].querySelector('.cube-bar-label').textContent).toBe('Creature');
        expect(rows[0].querySelector('.cube-bar-value').textContent).toBe('4.20 / pack · 40');
        expect(rows[0].querySelector('.cube-bar-fill').style.width).toBe('100%'); // tallest
    });

    test('renderRarity shows the rarity, as-fan and count', () => {
        const container = document.createElement('div');
        Cube.renderRarity(container, [{ rarity: 'Common', count: 90, asFan: 5.0 }]);
        const row = container.querySelector('.cube-bar-row');
        expect(row.querySelector('.cube-bar-label').textContent).toBe('Common');
        expect(row.querySelector('.cube-bar-value').textContent).toBe('5.00 / pack · 90');
    });

    test('renderColorPairs lists each guild with its count', () => {
        const container = document.createElement('div');
        Cube.renderColorPairs(container, [
            { pair: 'Azorius (WU)', count: 12 },
            { pair: 'Simic (GU)', count: 6 },
        ]);
        const rows = container.querySelectorAll('.cube-bar-row');
        expect(rows).toHaveLength(2);
        expect(rows[0].querySelector('.cube-bar-label').textContent).toBe('Azorius (WU)');
        expect(rows[0].querySelector('.cube-bar-value').textContent).toBe('12');
        expect(rows[0].querySelector('.cube-bar-fill').style.width).toBe('100%'); // tallest
    });

    test('renderColorPips tints each bar with its colour swatch', () => {
        const container = document.createElement('div');
        Cube.renderColorPips(container, [{ color: 'White', count: 40 }, { color: 'Blue', count: 20 }]);
        const rows = container.querySelectorAll('.cube-bar-row');
        expect(rows[0].querySelector('.cube-bar-label').textContent).toBe('White');
        // The pip bar is tinted with the colour-pie swatch, not the neutral grey
        // (jsdom reports the colour as rgb(...), so just assert it's set and not neutral).
        const bg = rows[0].querySelector('.cube-bar-fill').style.background;
        expect(bg).not.toBe('');
        expect(bg).not.toBe('rgb(122, 122, 138)'); // NEUTRAL_BAR #7a7a8a
        expect(rows[1].querySelector('.cube-bar-fill').style.width).toBe('50%');
    });

    test('renderDuplicates warns on non-basic duplicates and hides when there are none', () => {
        const el = document.createElement('p');
        Cube.renderDuplicates(el, [{ name: 'Sol Ring', count: 2 }, { name: 'Mana Crypt', count: 3 }]);
        expect(el.hidden).toBe(false);
        expect(el.textContent).toContain('2 non-basic cards duplicated');
        expect(el.textContent).toContain('Sol Ring ×2');
        expect(el.textContent).toContain('Mana Crypt ×3');

        Cube.renderDuplicates(el, []);
        expect(el.hidden).toBe(true);
        expect(el.textContent).toBe('');
    });

    test('renderAnalytics fills every panel and reveals the breakdown', () => {
        const dupes = document.createElement('p');
        const breakdown = document.createElement('details');
        breakdown.hidden = true;
        const avgMv = document.createElement('p');
        const curve = document.createElement('div');
        const types = document.createElement('div');
        const rarity = document.createElement('div');
        const pairs = document.createElement('div');
        const pips = document.createElement('div');
        const value = document.createElement('p');
        value.hidden = true;
        const extremes = document.createElement('div');
        extremes.hidden = true;
        const valueCurrency = document.createElement('select');
        valueCurrency.innerHTML = '<option value="usd">USD</option><option value="eur">EUR</option>';
        valueCurrency.hidden = true;
        Cube.renderAnalytics(
            {
                curve: [{ label: '0', count: 1 }, { label: '1', count: 2 }],
                averageManaValue: 2.5,
                nonLandCount: 3,
                types: [{ type: 'Creature', count: 2, asFan: 1.0 }],
                rarities: [{ rarity: 'Rare', count: 1, asFan: 0.5 }],
                duplicates: [{ name: 'Sol Ring', count: 2 }],
                colorPairs: [{ pair: 'Azorius (WU)', count: 4 }],
                colorPips: [{ color: 'White', count: 6 }],
                totalValues: [{ currency: 'usd', display: 'USD', amount: 42.5 }, { currency: 'eur', display: 'EUR', amount: 38 }],
                valueExtremes: [{ currency: 'usd', display: 'USD', mostName: 'Pricey', mostAmount: 40, leastName: 'Cheap', leastAmount: 0.5 }],
            },
            { dupes: dupes, breakdown: breakdown, avgMv: avgMv, curve: curve, types: types, rarity: rarity, pairs: pairs, pips: pips, value: value, extremes: extremes, valueCurrency: valueCurrency },
        );
        expect(breakdown.hidden).toBe(false);
        expect(avgMv.textContent).toContain('Average mana value 2.50');
        expect(avgMv.textContent).toContain('3 nonland cards');
        expect(curve.querySelectorAll('.cube-bar-row')).toHaveLength(2);
        expect(types.querySelector('.cube-bar-label').textContent).toBe('Creature');
        expect(rarity.querySelector('.cube-bar-label').textContent).toBe('Rare');
        expect(pairs.querySelector('.cube-bar-label').textContent).toBe('Azorius (WU)');
        expect(pips.querySelector('.cube-bar-label').textContent).toBe('White');
        expect(dupes.hidden).toBe(false);
        // Total value renders in the select's currency (USD), and the switch is revealed.
        expect(value.hidden).toBe(false);
        expect(value.textContent).toContain('$42.50');
        expect(valueCurrency.hidden).toBe(false);
        // Value extremes render in the same currency.
        expect(extremes.hidden).toBe(false);
        expect(extremes.textContent).toContain('Pricey ($40.00)');
        expect(extremes.textContent).toContain('Cheap ($0.50)');
    });

    test('renderAnalytics hides the currency switch when nothing is priced', () => {
        const value = document.createElement('p');
        const valueCurrency = document.createElement('select');
        Cube.renderAnalytics(
            { curve: [], averageManaValue: 0, nonLandCount: 0, types: [], rarities: [], duplicates: [], totalValues: [] },
            { dupes: document.createElement('p'), breakdown: document.createElement('details'), value: value, valueCurrency: valueCurrency },
        );
        expect(value.hidden).toBe(true);
        expect(valueCurrency.hidden).toBe(true);
    });

    test('renderAnalytics tolerates a missing payload and an all-land pool', () => {
        const breakdown = document.createElement('details');
        const dupes = document.createElement('p');
        Cube.renderAnalytics(null, { dupes: dupes, breakdown: breakdown });
        expect(breakdown.hidden).toBe(true);
        expect(dupes.hidden).toBe(true);

        const avgMv = document.createElement('p');
        Cube.renderAnalytics(
            { curve: [], averageManaValue: 0, nonLandCount: 0, types: [], rarities: [], duplicates: [] },
            { dupes: dupes, breakdown: breakdown, avgMv: avgMv, curve: document.createElement('div'), types: document.createElement('div'), rarity: document.createElement('div') },
        );
        expect(avgMv.textContent).toBe('No nonland cards.');
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

    test('ignores an unterminated brace rather than throwing', () => {
        expect(Cube.manaSymbolUrls('{R')).toEqual([]); // no closing brace → no match
        // A trailing unterminated token is dropped; the valid ones still parse.
        expect(Cube.manaSymbolUrls('{W}{U}{B').map((u) => u.symbol)).toEqual(['{W}', '{U}']);
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
        // magic.js wires the jsdom document on import, creating the overlay.
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
        expect(Cube.deleteListUrl('My Cube')).toBe('/magic/api/lists?name=My%20Cube');
        expect(Cube.deleteListUrl('Pauper / Peasant')).toBe('/magic/api/lists?name=Pauper%20%2F%20Peasant');
    });
});

describe('absoluteUrl', () => {
    test('joins the page origin with the relative share path', () => {
        expect(Cube.absoluteUrl('https://toby-bot.co.uk', '/magic/c/abc123'))
            .toBe('https://toby-bot.co.uk/magic/c/abc123');
    });

    test('tolerates a missing origin', () => {
        expect(Cube.absoluteUrl(null, '/magic/c/abc123')).toBe('/magic/c/abc123');
    });
});

describe('queryShareUrl', () => {
    test('builds a ?q= deep link, encoding the query', () => {
        expect(Cube.queryShareUrl('https://toby-bot.co.uk', 't:dragon c:r'))
            .toBe('https://toby-bot.co.uk/magic?q=t%3Adragon%20c%3Ar');
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

describe('prefillFromUrl lands a shared link on a cube tab', () => {
    // A ?q= / ?list= share link prefills the shared "Your cube" source, which
    // only shows on the cube tabs — but the page defaults to Card lookup, where
    // it's hidden. These prove the prefill switches to a cube tab to reveal it.
    let host;
    afterEach(() => {
        window.history.replaceState(null, '', '/magic');
        window.location.hash = '';
        if (host && host.parentNode) host.parentNode.removeChild(host);
        host = null;
    });

    function setUp() {
        host = document.createElement('div');
        host.innerHTML =
            '<section class="cube-source-card" data-needs-cube hidden></section>' +
            '<div data-source-tab="search" aria-selected="false"></div>' +
            '<div data-source-tab="list" aria-selected="false"></div>' +
            '<div data-source-panel="search"><input name="query"></div>' +
            '<div data-source-panel="list" hidden><textarea name="list"></textarea></div>' +
            '<button role="tab" data-tab="card" aria-selected="true"></button>' +
            '<button role="tab" data-tab="preview" aria-selected="false"></button>' +
            '<section data-panel="card"></section>' +
            '<section data-panel="preview" hidden></section>';
        document.body.appendChild(host);
    }

    test('a ?q= link fills the search box and switches to the preview tab', () => {
        setUp();
        window.history.replaceState(null, '', '/magic?q=cube%3Avintage');
        Cube.prefillFromUrl(document);

        expect(document.querySelector('[data-source-panel="search"] input[name="query"]').value).toBe('cube:vintage');
        expect(document.querySelector('[data-tab="preview"]').getAttribute('aria-selected')).toBe('true');
        expect(document.querySelector('[data-panel="preview"]').hidden).toBe(false);
        expect(document.querySelector('[data-needs-cube]').hidden).toBe(false);
    });

    test('a ?list= link fills the list box and switches to the preview tab', () => {
        setUp();
        window.history.replaceState(null, '', '/magic?list=Bolt%0AForest');
        Cube.prefillFromUrl(document);

        expect(document.querySelector('textarea[name="list"]').value).toBe('Bolt\nForest');
        expect(document.querySelector('[data-tab="preview"]').getAttribute('aria-selected')).toBe('true');
    });

    test('an explicit #hash deep-link wins over the prefill default', () => {
        setUp();
        window.history.replaceState(null, '', '/magic?q=cube%3Avintage#card');
        Cube.prefillFromUrl(document);

        // The query is still prefilled, but the chosen tab is left to wireTabs.
        expect(document.querySelector('[data-source-panel="search"] input[name="query"]').value).toBe('cube:vintage');
        expect(document.querySelector('[data-tab="preview"]').getAttribute('aria-selected')).toBe('false');
    });

    test('no prefill leaves the tabs untouched', () => {
        setUp();
        window.history.replaceState(null, '', '/magic');
        Cube.prefillFromUrl(document);

        expect(document.querySelector('[data-tab="preview"]').getAttribute('aria-selected')).toBe('false');
        expect(document.querySelector('[data-panel="preview"]').hidden).toBe(true);
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
