// Magic toolkit (/magic), layer 1 of 3: the pure helpers — formatting,
// colours, URL builders, request bodies and the pack-list text format.
// No DOM and no network, so every function here is directly testable.
// Loaded first; `magic-render.js` and `magic.js` build on it.
(function (root) {
    'use strict';

    const TABS = ['card', 'legality', 'watch', 'reference', 'generate', 'preview', 'asfan', 'compare'];

    // The tab shown on load when there's no #hash deep-link. The toolkit leads
    // with the broadly-useful card lookup rather than the niche cube builder.
    const DEFAULT_TAB = 'card';

    // Tools that work off their own inputs, not the shared "Your cube" source.
    const NO_SHARED_SOURCE = ['asfan', 'compare', 'card', 'legality', 'reference', 'watch'];

    // Colour-pie palette for swatches/bars. Tuned to read on the dark
    // dashboard background while staying recognisably W/U/B/R/G + gold
    // multicolour, silver colourless, brown land.
    const CATEGORY_COLORS = {
        White: '#f5edd6',
        Blue: '#3b82f6',
        Black: '#9aa0b5',
        Red: '#ef4444',
        Green: '#22c55e',
        Multicolor: '#f1c40f',
        Colorless: '#c7ccd4',
        Land: '#b9895a',
    };

    function categoryColor(name) {
        return CATEGORY_COLORS[name] || '#7a7a8a';
    }

    function formatAsFan(value) {
        return Number(value).toFixed(2);
    }

    function asfanSentence(value, packSize) {
        return 'On average you\'ll open about ' + formatAsFan(value) +
            ' of these in a ' + packSize + '-card pack.';
    }

    /**
     * The one-line "stat line" shown under the hover-zoom: the type line
     * plus mana value (omitted for 0-cost cards like lands). e.g.
     * "Instant · MV 1", or just "Basic Land — Forest".
     */
    function cardStatline(typeLine, manaValue) {
        const parts = [];
        if (typeLine) parts.push(typeLine);
        const mv = Number(manaValue);
        if (mv > 0) parts.push('MV ' + (Number.isInteger(mv) ? mv : mv));
        return parts.join(' · ');
    }

    /**
     * Scryfall card-symbol SVG URLs for a mana cost like "{1}{R}{R}". Each
     * {sym} maps to svgs.scryfall.io/card-symbols/<code>.svg, where <code> is
     * the symbol with braces and slashes removed ({W/U} -> WU). Pure, so it's
     * unit-testable; returns [] for null/empty/costless cards.
     */
    function manaSymbolUrls(manaCost) {
        if (!manaCost) return [];
        const out = [];
        const re = /\{([^}]+)\}/g;
        let m;
        while ((m = re.exec(manaCost)) !== null) {
            const code = m[1].replace(/\//g, '').toUpperCase();
            out.push({
                symbol: '{' + m[1] + '}',
                url: 'https://svgs.scryfall.io/card-symbols/' + encodeURIComponent(code) + '.svg',
            });
        }
        return out;
    }

    /** Exact-name Scryfall search for a card, so the link opens that card. */
    function scryfallCardUrl(name) {
        return 'https://scryfall.com/search?q=' + encodeURIComponent('!"' + name + '"');
    }

    /** The tab id encoded in a URL hash, or null if it isn't a real tab. */
    function tabIdFromHash(hash) {
        const id = (hash || '').replace(/^#/, '');
        return TABS.indexOf(id) >= 0 ? id : null;
    }

    /** "1 card" / "15 cards" — the pack size, pluralised. */
    function cardCountLabel(n) {
        return n + (n === 1 ? ' card' : ' cards');
    }

    /**
     * The header opening one pack in a downloaded pack list. Mirrors
     * PackListWriter.header on the Kotlin side (the bot writes the same
     * format), so a file saved from either end loads on either end.
     */
    function packHeader(index, size) {
        return '== Pack ' + index + ' (' + cardCountLabel(size) + ') ==';
    }

    /**
     * The `#` line saying where a deal came from. A comment, so it's ignored
     * on the way back in — it exists so a folder of cube-packs.txt files can
     * be told apart. Mirrors PackListWriter.provenance on the Kotlin side.
     */
    function provenanceLine(packs, meta) {
        const size = packs.reduce(function (max, p) { return Math.max(max, p.length); }, 0);
        const shape = packs.length + (packs.length === 1 ? ' pack of ' : ' packs of ') + size;
        const parts = [];
        if (meta && meta.source) parts.push(meta.source);
        parts.push(shape);
        if (meta && meta.dealtOn) parts.push(meta.dealtOn);
        return '# ' + parts.join(' — ');
    }

    /**
     * Plain-text rendering of the dealt packs, for the download button.
     * [meta] fills in the provenance line ({ source, dealtOn }).
     */
    function packsToText(packs, meta) {
        if (!packs.length) return '';
        const body = packs.map(function (pack, i) {
            return packHeader(i + 1, pack.length) + '\n' +
                pack.map(function (c) { return '  ' + c.name; }).join('\n');
        }).join('\n\n') + '\n';
        // A reloaded deal keeps the line its file already had, so saving it
        // again doesn't relabel someone else's deal as dealt by you today.
        const line = (meta && meta.line) || provenanceLine(packs, meta);
        return line + '\n\n' + body;
    }

    /** Today, as the ISO date the provenance line carries. */
    function today() {
        return new Date().toISOString().slice(0, 10);
    }

    /**
     * The most text the pack-import endpoint accepts, mirroring the service's
     * own cap. File size is in bytes and the cap is in characters, so this
     * only ever rejects early — never lets something oversized through.
     */
    const MAX_IMPORT_BYTES = 100000;

    /**
     * Why a chosen file can't be loaded as a pack list, or null when it's
     * fine. Checked before reading so an accidental pick of something huge
     * fails instantly rather than after a long read. Pure so it's testable.
     */
    function importFileError(file) {
        if (!file) return 'Choose a pack list (.txt) to load.';
        if (!file.size) return 'That file is empty.';
        if (file.size > MAX_IMPORT_BYTES) return 'That file is too big (max 100,000 characters).';
        return null;
    }

    /** The request that reloads a downloaded deal. Pure so it's unit-testable. */
    function packImportRequest(text) {
        return { method: 'POST', url: '/magic/api/packs', body: { text: text } };
    }

    /** The request that publishes a dealt set of packs as a link. */
    function shareDealRequest(packs, name) {
        return {
            method: 'POST',
            url: '/magic/api/share-deal',
            body: { name: name || '', packs: packsToText(packs) },
        };
    }

    /** The headline for a reloaded deal — no pack maths, these were already dealt. */
    function packsSummary(json) {
        const count = Number(json.packCount) || 0;
        return 'Loaded ' + count + (count === 1 ? ' pack' : ' packs') + ' from your file — ' +
            json.poolSize + ' cards, exactly as they were dealt. Click any card to view it on Scryfall.' +
            (json.note ? ' ' + json.note : '');
    }

    /**
     * The cube's cards as "<count> <name>" lines, from the de-duped preview
     * groups (count is the copies in the pool, so "10 Forest"). A
     * re-importable decklist — the read side of CardListParser.
     */
    function groupsToList(groups) {
        const lines = [];
        (groups || []).forEach(function (g) {
            (g.cards || []).forEach(function (c) {
                lines.push((c.count && c.count > 1 ? c.count : 1) + ' ' + c.name);
            });
        });
        return lines;
    }

    function cubeListText(groups) {
        return groupsToList(groups).join('\n') + '\n';
    }

    function asfanUrl(p) {
        const q = new URLSearchParams({ total: p.total, cubeSize: p.cubeSize, packSize: p.packSize });
        return '/magic/api/asfan?' + q.toString();
    }

    function previewUrl(p) {
        const q = new URLSearchParams({ query: p.query, packSize: p.packSize });
        return '/magic/api/preview?' + q.toString();
    }

    function generateUrl(p) {
        const q = new URLSearchParams({
            query: p.query,
            packs: p.packs,
            packSize: p.packSize,
            balanced: p.balanced ? 'true' : 'false',
        });
        return '/magic/api/generate?' + q.toString();
    }

    /**
     * The request to deal ONE balanced pack from a preview's source — reuses
     * the generate endpoint (packs=1). GET for a Scryfall search, POST for a
     * pasted list. Pure so it's unit-testable.
     */
    function samplePackRequest(src, packSize) {
        const ps = Number(packSize) || 15;
        if (src.mode === 'list') {
            return { method: 'POST', url: '/magic/api/generate', body: { list: src.list, packs: 1, packSize: ps, balanced: true } };
        }
        return { method: 'GET', url: generateUrl({ query: src.query, packs: 1, packSize: ps, balanced: true }) };
    }

    // A neutral bar colour for analytics with no colour-pie meaning (curve,
    // types, rarity), matching the categoryColor fallback.
    const NEUTRAL_BAR = '#7a7a8a';

    // Market currencies, mirroring common.mtg.MtgCurrency. The `code` matches
    // the AnalyticsView total-value entries and the currency-toggle option
    // values; symbol/suffix format an amount ($1.50 / €1.50 / 1.50 tix).
    const CURRENCIES = [
        { code: 'usd', display: 'USD', symbol: '$', suffix: '' },
        { code: 'eur', display: 'EUR', symbol: '€', suffix: '' },
        { code: 'tix', display: 'Tix', symbol: '', suffix: ' tix' },
    ];

    function currencyMeta(code) {
        return CURRENCIES.filter(function (c) { return c.code === code; })[0] || CURRENCIES[0];
    }

    /** The total-value line for [code] from a totals list, or '' when nothing is priced in it. Pure. */
    function totalValueText(totalValues, code) {
        const match = (totalValues || []).filter(function (t) { return t.currency === code; })[0];
        if (!match) return '';
        const m = currencyMeta(code);
        return '≈ ' + m.symbol + Number(match.amount).toFixed(2) + m.suffix + ' in priced cards (' + m.display + ')';
    }

    /** Formats one currency amount, e.g. "$1.50" / "1.50 tix". Pure. */
    function formatMoney(amount, code) {
        const m = currencyMeta(code);
        return m.symbol + Number(amount).toFixed(2) + m.suffix;
    }

    /** GET URL for the single-card lookup. */
    function cardUrl(name) {
        return '/magic/api/card?name=' + encodeURIComponent(name);
    }

    /**
     * GET URL for a card search, carrying only the filled-in filters
     * (name / type / advanced Scryfall query) as query params. Pure.
     */
    function searchUrl(filters) {
        const params = new URLSearchParams();
        if (filters.name) params.set('name', filters.name);
        if (filters.type) params.set('type', filters.type);
        if (filters.query) params.set('query', filters.query);
        return '/magic/api/search?' + params.toString();
    }

    /**
     * A Scryfall-style headline for a search result: "7 cards match" (or
     * "750+ cards match" when the search hit the fetch ceiling). Pure.
     */
    function searchSentence(total, capped) {
        const n = total + (capped ? '+' : '');
        return n + ' card' + (total === 1 ? '' : 's') + ' match';
    }

    /** GET URL for a card's official rulings. */
    function rulingsUrl(name) {
        return '/magic/api/rulings?name=' + encodeURIComponent(name);
    }

    /**
     * A card's market prices as a compact one-liner ("$1.50 · €1.20 · 0.03 tix"),
     * present currencies only, or '' when Scryfall has no price for it. Pure.
     */
    function priceLine(card) {
        const parts = [];
        if (card.priceUsd) parts.push('$' + card.priceUsd);
        if (card.priceEur) parts.push('€' + card.priceEur);
        if (card.priceTix) parts.push(card.priceTix + ' tix');
        return parts.join(' · ');
    }

    /** GET URL for a card's combos. */
    function combosUrl(name) {
        return '/magic/api/combos?name=' + encodeURIComponent(name);
    }

    /** POST body builder isn't needed; the URL is constant. */
    function legalityUrl() {
        return '/magic/api/legality';
    }

    /**
     * The link to one seat's pack out of a shared deal. Pure so it's
     * unit-testable; mirrors the `/magic/d/{token}/{pack}` route.
     */
    function dealPackUrl(origin, token, index) {
        return absoluteUrl(origin, '/magic/d/' + encodeURIComponent(token) + '/' + index);
    }

    /**
     * Where to put the hover-zoom image: offset from the cursor, flipped to
     * the cursor's other side if it would overflow, and clamped inside the
     * viewport with a small margin. Pure so it's unit-testable.
     */
    function zoomPosition(px, py, w, h, vw, vh) {
        const OFFSET = 18;
        const MARGIN = 8;
        let left = px + OFFSET;
        if (left + w > vw - MARGIN) left = px - OFFSET - w; // flip to the left
        if (left < MARGIN) left = MARGIN;
        let top = py - h / 2;
        if (top + h > vh - MARGIN) top = vh - h - MARGIN;
        if (top < MARGIN) top = MARGIN;
        return { left: left, top: top };
    }

    // --- Deep links and pasted-list counting ---------------------------

    /** A shareable URL that pre-loads a Scryfall query (no login needed). */
    function queryShareUrl(origin, query) {
        return (origin || '') + '/magic?q=' + encodeURIComponent(query);
    }

    /** Reads ?q= / ?list= prefill params from a URL search string. */
    function readUrlPrefill(search) {
        const params = new URLSearchParams(search || '');
        const out = {};
        const q = params.get('q');
        const list = params.get('list');
        if (q) out.q = q;
        if (list) out.list = list;
        return out;
    }

    /** Counts the card lines in a pasted list (skips blanks and comments). */
    function countCards(text) {
        return (text || '').split('\n')
            .map(function (line) { return line.trim(); })
            .filter(function (line) {
                return line !== '' && line.indexOf('#') !== 0 && line.indexOf('//') !== 0;
            })
            .length;
    }

    // --- Saved lists and share links (the URL half) --------------------

    const LISTS_API = '/magic/api/lists';

    /** DELETE URL for a saved list, with the name safely encoded. */
    function deleteListUrl(name) {
        return LISTS_API + '?name=' + encodeURIComponent(name);
    }

    /** Joins the page origin with a server-returned relative share path. */
    function absoluteUrl(origin, path) {
        return (origin || '') + path;
    }

    /** How many unresolved names to spell out before summarising the rest. */
    const MAX_NOT_FOUND_NAMES = 10;

    /**
     * The "couldn't find these names" sentence. A badly-mangled list can fail
     * on hundreds of names, so only the first few are spelled out — the rest
     * are counted. Pure so it's unit-testable.
     */
    function notFoundText(names) {
        const shown = names.slice(0, MAX_NOT_FOUND_NAMES);
        const rest = names.length - shown.length;
        return 'Couldn’t find ' + cardCountLabel(names.length) + ': ' + shown.join(', ') +
            (rest > 0 ? ', and ' + rest + ' more' : '');
    }

    // --- Card-name autocomplete (Scryfall) -----------------------------

    const MAX_CARD_SUGGEST = 10;

    // Card-search autosearch: how long to wait after the last keystroke before
    // firing, and the shortest filter that auto-fires (the Search button has no
    // minimum). Tuned to coalesce typing into one request and stay polite to
    // Scryfall.
    const AUTO_SEARCH_DEBOUNCE_MS = 400;
    const AUTO_SEARCH_MIN = 3;

    function scryfallAutocompleteUrl(query) {
        return 'https://api.scryfall.com/cards/autocomplete?q=' + encodeURIComponent(query);
    }

    /** The line of [value] the caret sits on: its bounds and text. */
    function currentLineInfo(value, caret) {
        const start = value.lastIndexOf('\n', caret - 1) + 1;
        let end = value.indexOf('\n', caret);
        if (end < 0) end = value.length;
        return { start: start, end: end, text: value.slice(start, end) };
    }

    /** Splits a leading quantity (`3 `, `3x `) off a decklist line. */
    function splitQuantityPrefix(line) {
        const m = line.match(/^(\d+[xX]?\s+)/);
        return m ? { prefix: m[1], name: line.slice(m[1].length) } : { prefix: '', name: line };
    }

    /** Replaces the card name on the caret's line with [choice], keeping the quantity. */
    function applyCardChoice(value, caret, choice) {
        const line = currentLineInfo(value, caret);
        const newLine = splitQuantityPrefix(line.text).prefix + choice;
        return {
            value: value.slice(0, line.start) + newLine + value.slice(line.end),
            caret: line.start + newLine.length,
        };
    }

    /** GET URL for a set lookup by code. */
    function setUrl(code) {
        return '/magic/api/set?code=' + encodeURIComponent(code);
    }

    /** GET URL for a glossary keyword lookup. */
    function ruleUrl(term) {
        return '/magic/api/rule?term=' + encodeURIComponent(term);
    }

    const WATCHES_API = '/magic/api/watches';

    /** Formats a watch as "Card — below $30 (USD)". Pure. */
    function watchLine(watch) {
        const m = currencyMeta(watch.currency);
        return watch.cardName + ' — ' + watch.direction + ' ' + m.symbol +
            Number(watch.threshold).toFixed(2) + m.suffix + ' (' + m.display + ')';
    }


    const api = {
        AUTO_SEARCH_DEBOUNCE_MS: AUTO_SEARCH_DEBOUNCE_MS,
        AUTO_SEARCH_MIN: AUTO_SEARCH_MIN,
        DEFAULT_TAB: DEFAULT_TAB,
        LISTS_API: LISTS_API,
        MAX_CARD_SUGGEST: MAX_CARD_SUGGEST,
        NEUTRAL_BAR: NEUTRAL_BAR,
        NO_SHARED_SOURCE: NO_SHARED_SOURCE,
        TABS: TABS,
        WATCHES_API: WATCHES_API,
        absoluteUrl: absoluteUrl,
        applyCardChoice: applyCardChoice,
        asfanSentence: asfanSentence,
        asfanUrl: asfanUrl,
        cardCountLabel: cardCountLabel,
        cardStatline: cardStatline,
        cardUrl: cardUrl,
        categoryColor: categoryColor,
        combosUrl: combosUrl,
        countCards: countCards,
        cubeListText: cubeListText,
        currentLineInfo: currentLineInfo,
        dealPackUrl: dealPackUrl,
        deleteListUrl: deleteListUrl,
        formatAsFan: formatAsFan,
        formatMoney: formatMoney,
        generateUrl: generateUrl,
        groupsToList: groupsToList,
        importFileError: importFileError,
        legalityUrl: legalityUrl,
        manaSymbolUrls: manaSymbolUrls,
        notFoundText: notFoundText,
        packHeader: packHeader,
        packImportRequest: packImportRequest,
        packsSummary: packsSummary,
        packsToText: packsToText,
        previewUrl: previewUrl,
        priceLine: priceLine,
        provenanceLine: provenanceLine,
        queryShareUrl: queryShareUrl,
        readUrlPrefill: readUrlPrefill,
        ruleUrl: ruleUrl,
        rulingsUrl: rulingsUrl,
        samplePackRequest: samplePackRequest,
        scryfallAutocompleteUrl: scryfallAutocompleteUrl,
        scryfallCardUrl: scryfallCardUrl,
        searchSentence: searchSentence,
        searchUrl: searchUrl,
        setUrl: setUrl,
        shareDealRequest: shareDealRequest,
        splitQuantityPrefix: splitQuantityPrefix,
        tabIdFromHash: tabIdFromHash,
        today: today,
        totalValueText: totalValueText,
        watchLine: watchLine,
        zoomPosition: zoomPosition,
    };
    if (root) root.TobyCubeFormat = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
