// MTG cube workshop page. A guided, tabbed tool (generate packs / preview
// a cube / as-fan calculator) over the /cube/api/* JSON endpoints. The
// emphasis is on showing the actual CARDS: generated packs and the preview
// both list real card names linked to Scryfall. Pure helpers (URL builders,
// formatting, colours, card links, hash↔tab mapping, DOM renderers) are
// exported for Jest; the DOM wiring only runs in a browser.
(function (root) {
    'use strict';

    const TABS = ['generate', 'preview', 'asfan', 'compare', 'card'];

    // Tools that work off their own inputs, not the shared "Your cube" source.
    const NO_SHARED_SOURCE = ['asfan', 'compare', 'card'];

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

    /** Plain-text rendering of the dealt packs, for the download button. */
    function packsToText(packs) {
        return packs.map(function (pack, i) {
            return '== Pack ' + (i + 1) + ' (' + pack.length + ' cards) ==\n' +
                pack.map(function (c) { return '  ' + c.name; }).join('\n');
        }).join('\n\n') + '\n';
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
        return '/cube/api/asfan?' + q.toString();
    }

    function previewUrl(p) {
        const q = new URLSearchParams({ query: p.query, packSize: p.packSize });
        return '/cube/api/preview?' + q.toString();
    }

    function generateUrl(p) {
        const q = new URLSearchParams({
            query: p.query,
            packs: p.packs,
            packSize: p.packSize,
            balanced: p.balanced ? 'true' : 'false',
        });
        return '/cube/api/generate?' + q.toString();
    }

    /**
     * The request to deal ONE balanced pack from a preview's source — reuses
     * the generate endpoint (packs=1). GET for a Scryfall search, POST for a
     * pasted list. Pure so it's unit-testable.
     */
    function samplePackRequest(src, packSize) {
        const ps = Number(packSize) || 15;
        if (src.mode === 'list') {
            return { method: 'POST', url: '/cube/api/generate', body: { list: src.list, packs: 1, packSize: ps, balanced: true } };
        }
        return { method: 'GET', url: generateUrl({ query: src.query, packs: 1, packSize: ps, balanced: true }) };
    }

    /**
     * A card as a thumbnail tile linking to its Scryfall page. Falls back
     * to a captioned placeholder box when Scryfall has no image for it.
     * Images lazy-load so a 500-card preview doesn't fetch everything at once.
     */
    function cardTile(card) {
        const a = document.createElement('a');
        a.className = 'cube-card';
        a.href = scryfallCardUrl(card.name);
        a.target = '_blank';
        a.rel = 'noopener';
        a.title = card.name;
        // The larger image + stat line drive the hover-to-enlarge preview.
        if (card.imageUrlLarge) a.setAttribute('data-large', card.imageUrlLarge);
        a.setAttribute('data-statline', cardStatline(card.typeLine, card.manaValue));
        if (card.imageUrl) {
            const img = document.createElement('img');
            img.className = 'cube-card-img';
            img.src = card.imageUrl;
            img.alt = card.name;
            img.setAttribute('loading', 'lazy');
            img.setAttribute('width', '146');
            img.setAttribute('height', '204');
            a.appendChild(img);
        } else {
            const placeholder = document.createElement('span');
            placeholder.className = 'cube-card-img cube-card-img-empty';
            a.appendChild(placeholder);
        }
        const name = document.createElement('span');
        name.className = 'cube-card-name';
        name.textContent = card.name;
        a.appendChild(name);

        // Mana cost as a row of Scryfall symbol SVGs, so the cost is scannable
        // without squinting at the thumbnail.
        const symbols = manaSymbolUrls(card.manaCost);
        if (symbols.length) {
            const mana = document.createElement('span');
            mana.className = 'cube-card-mana';
            symbols.forEach(function (s) {
                const sym = document.createElement('img');
                sym.className = 'cube-mana-symbol';
                sym.src = s.url;
                sym.alt = s.symbol;
                sym.setAttribute('loading', 'lazy');
                mana.appendChild(sym);
            });
            a.appendChild(mana);
        }

        // Copy count for de-duped preview tiles ("×10 Forest").
        if (card.count && card.count > 1) {
            const qty = document.createElement('span');
            qty.className = 'cube-card-qty';
            qty.textContent = '×' + card.count;
            a.appendChild(qty);
        }

        // Double-faced cards get a flip control that swaps the thumbnail and
        // the hover/lightbox image between the front and back faces.
        if (card.imageUrlBack) {
            a.setAttribute('data-back', card.imageUrlBack);
            a.setAttribute('data-front-large', card.imageUrlLarge || card.imageUrl || '');
            a.setAttribute('data-front-thumb', card.imageUrl || card.imageUrlLarge || '');
            const flip = document.createElement('button');
            flip.type = 'button';
            flip.className = 'cube-card-flip';
            flip.setAttribute('aria-label', 'Flip card');
            flip.title = 'Flip card';
            flip.textContent = '⇄';
            a.appendChild(flip);
        }
        return a;
    }

    // A neutral bar colour for analytics with no colour-pie meaning (curve,
    // types, rarity), matching the categoryColor fallback.
    const NEUTRAL_BAR = '#7a7a8a';

    /** A length-scaled bar track from a 0..1 [ratio] in [color]. */
    function barTrack(ratio, color) {
        const track = document.createElement('div');
        track.className = 'cube-bar-track';
        const fill = document.createElement('div');
        fill.className = 'cube-bar-fill';
        fill.style.width = (ratio > 0 ? Math.max(2, Math.round(ratio * 100)) : 0) + '%';
        fill.style.background = color;
        track.appendChild(fill);
        return track;
    }

    function asFanBar(category, asFan, max) {
        return barTrack(max > 0 ? asFan / max : 0, categoryColor(category));
    }

    /** A `label | bar | value` row (no colour swatch) for the analytics panels. */
    function statRow(labelText, valueText, ratio, color) {
        const rowEl = document.createElement('div');
        rowEl.className = 'cube-bar-row';
        const label = document.createElement('span');
        label.className = 'cube-bar-label';
        label.textContent = labelText;
        const value = document.createElement('span');
        value.className = 'cube-bar-value';
        value.textContent = valueText;
        rowEl.appendChild(label);
        rowEl.appendChild(barTrack(ratio, color));
        rowEl.appendChild(value);
        return rowEl;
    }

    /** The mana curve: one bar per bucket ("0".."7+"), scaled to the tallest. */
    function renderManaCurve(container, curve) {
        container.replaceChildren();
        const max = curve.reduce(function (m, b) { return Math.max(m, b.count); }, 0);
        curve.forEach(function (b) {
            container.appendChild(statRow(b.label, String(b.count), max > 0 ? b.count / max : 0, NEUTRAL_BAR));
        });
        return container;
    }

    /** The card-type breakdown: count + as-fan per type. */
    function renderTypeBreakdown(container, types) {
        container.replaceChildren();
        const max = types.reduce(function (m, t) { return Math.max(m, t.asFan); }, 0);
        types.forEach(function (t) {
            container.appendChild(statRow(t.type, formatAsFan(t.asFan) + ' / pack · ' + t.count, max > 0 ? t.asFan / max : 0, NEUTRAL_BAR));
        });
        return container;
    }

    /** The rarity breakdown: count + as-fan per rarity. */
    function renderRarity(container, rarities) {
        container.replaceChildren();
        const max = rarities.reduce(function (m, r) { return Math.max(m, r.asFan); }, 0);
        rarities.forEach(function (r) {
            container.appendChild(statRow(r.rarity, formatAsFan(r.asFan) + ' / pack · ' + r.count, max > 0 ? r.asFan / max : 0, NEUTRAL_BAR));
        });
        return container;
    }

    /** The two-colour guild breakdown (archetype support). */
    function renderColorPairs(container, pairs) {
        container.replaceChildren();
        const max = pairs.reduce(function (m, p) { return Math.max(m, p.count); }, 0);
        pairs.forEach(function (p) {
            container.appendChild(statRow(p.pair, String(p.count), max > 0 ? p.count / max : 0, NEUTRAL_BAR));
        });
        return container;
    }

    /** The pip-weighted colour balance, each bar tinted with its colour swatch. */
    function renderColorPips(container, pips) {
        container.replaceChildren();
        const max = pips.reduce(function (m, p) { return Math.max(m, p.count); }, 0);
        pips.forEach(function (p) {
            container.appendChild(statRow(p.color, String(p.count), max > 0 ? p.count / max : 0, categoryColor(p.color)));
        });
        return container;
    }

    /** A singleton-violation warning; hidden when the cube has no non-basic duplicates. */
    function renderDuplicates(el, duplicates) {
        if (!el) return;
        if (!duplicates || !duplicates.length) { el.hidden = true; el.textContent = ''; return; }
        el.textContent = '⚠️ ' + duplicates.length + ' non-basic card' + (duplicates.length > 1 ? 's' : '') +
            ' duplicated: ' + duplicates.map(function (d) { return d.name + ' ×' + d.count; }).join(', ');
        el.hidden = false;
    }

    /**
     * Renders the whole cube report into the preview panels (duplicates
     * warning always-visible, the rest inside a collapsible). Tolerates a
     * missing analytics payload (older responses) and any missing element.
     */
    function renderAnalytics(analytics, els) {
        renderDuplicates(els.dupes, analytics && analytics.duplicates);
        if (!analytics) { hide(els.breakdown); return; }
        if (els.avgMv) {
            els.avgMv.textContent = analytics.nonLandCount > 0
                ? 'Average mana value ' + formatAsFan(analytics.averageManaValue) +
                    ' over ' + analytics.nonLandCount + ' nonland card' + (analytics.nonLandCount > 1 ? 's' : '')
                : 'No nonland cards.';
        }
        if (els.curve) renderManaCurve(els.curve, analytics.curve || []);
        if (els.types) renderTypeBreakdown(els.types, analytics.types || []);
        if (els.rarity) renderRarity(els.rarity, analytics.rarities || []);
        if (els.pairs) renderColorPairs(els.pairs, analytics.colorPairs || []);
        if (els.pips) renderColorPips(els.pips, analytics.colorPips || []);
        show(els.breakdown);
    }

    /** As-fan bars only (the secondary "balance" view under a generate). */
    function renderDistribution(container, distribution) {
        container.replaceChildren();
        const max = distribution.reduce(function (m, r) { return Math.max(m, r.asFan); }, 0);
        distribution.forEach(function (row) {
            const rowEl = document.createElement('div');
            rowEl.className = 'cube-bar-row';

            const label = document.createElement('span');
            label.className = 'cube-bar-label';
            const swatch = document.createElement('span');
            swatch.className = 'cube-swatch';
            swatch.style.background = categoryColor(row.category);
            label.appendChild(swatch);
            label.appendChild(document.createTextNode(row.category));

            const value = document.createElement('span');
            value.className = 'cube-bar-value';
            value.textContent = formatAsFan(row.asFan) + ' / pack · ' + row.count;

            rowEl.appendChild(label);
            rowEl.appendChild(asFanBar(row.category, row.asFan, max));
            rowEl.appendChild(value);
            container.appendChild(rowEl);
        });
        return container;
    }

    /** GET URL for the single-card lookup. */
    function cardUrl(name) {
        return '/cube/api/card?name=' + encodeURIComponent(name);
    }

    /** Renders a looked-up card: its (large) image beside a list of facts. */
    function renderCardLookup(container, card) {
        container.replaceChildren();
        const wrap = document.createElement('div');
        wrap.className = 'cube-cardlookup';

        const big = card.imageUrlLarge || card.imageUrl;
        if (big) {
            const img = document.createElement('img');
            img.className = 'cube-cardlookup-img';
            img.src = big;
            img.alt = card.name;
            wrap.appendChild(img);
        }

        const facts = document.createElement('div');
        facts.className = 'cube-cardlookup-facts';
        const h = document.createElement('h3');
        h.textContent = card.name;
        facts.appendChild(h);

        function fact(label, value) {
            if (value == null || value === '') return;
            const p = document.createElement('p');
            p.className = 'cube-cardlookup-fact';
            const b = document.createElement('strong');
            b.textContent = label + ' ';
            p.appendChild(b);
            p.appendChild(document.createTextNode(String(value)));
            facts.appendChild(p);
        }

        fact('Type', card.typeLine);
        const symbols = manaSymbolUrls(card.manaCost);
        if (symbols.length) {
            const p = document.createElement('p');
            p.className = 'cube-cardlookup-fact';
            const b = document.createElement('strong');
            b.textContent = 'Mana cost ';
            p.appendChild(b);
            symbols.forEach(function (s) {
                const sym = document.createElement('img');
                sym.className = 'cube-mana-symbol';
                sym.src = s.url;
                sym.alt = s.symbol;
                p.appendChild(sym);
            });
            facts.appendChild(p);
        }
        fact('Mana value', card.manaValue);
        fact('Rarity', card.rarity);
        fact('Colour identity', (card.colors && card.colors.length) ? card.colors.join(', ') : 'Colourless');

        const link = document.createElement('a');
        link.className = 'btn btn-secondary cube-cardlookup-link';
        link.href = scryfallCardUrl(card.name);
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = 'View on Scryfall ↗';
        facts.appendChild(link);

        wrap.appendChild(facts);
        container.appendChild(wrap);
        return container;
    }

    /** Renders a cube diff: Added / Removed / Count-changed sections of card names. */
    function renderDiff(container, data) {
        container.replaceChildren();
        function section(title, lines, sign) {
            if (!lines || !lines.length) return;
            const block = document.createElement('div');
            block.className = 'cube-diff-group';
            const h = document.createElement('h4');
            h.className = 'cube-diff-h';
            h.textContent = title + ' (' + lines.length + ')';
            block.appendChild(h);
            const ul = document.createElement('ul');
            ul.className = 'cube-diff-list';
            const prefix = sign === 'add' ? '+ ' : (sign === 'remove' ? '− ' : '~ ');
            lines.forEach(function (line) {
                const li = document.createElement('li');
                li.className = 'cube-diff-line cube-diff-' + sign;
                li.textContent = prefix + line.name +
                    (sign === 'change' ? ' (' + line.from + ' → ' + line.to + ')' : '');
                ul.appendChild(li);
            });
            block.appendChild(ul);
            container.appendChild(block);
        }
        section('Added', data.added, 'add');
        section('Removed', data.removed, 'remove');
        section('Count changed', data.changed, 'change');
        const total = (data.added || []).length + (data.removed || []).length + (data.changed || []).length;
        if (total === 0) {
            const p = document.createElement('p');
            p.className = 'cube-diff-empty';
            p.textContent = 'No differences — the two lists match.';
            container.appendChild(p);
        }
        return container;
    }

    /** A chevron that rotates when its parent <details> is open. */
    function collapseChevron() {
        const c = document.createElement('span');
        c.className = 'cube-collapse-chevron';
        c.setAttribute('aria-hidden', 'true');
        c.textContent = '▸';
        return c;
    }

    /**
     * A "Collapse all / Expand all" control for a result area full of
     * collapsible <details> sections — makes a 24-pack deal navigable.
     */
    function collapseToggle(container) {
        const bar = document.createElement('div');
        bar.className = 'cube-collapse-bar';
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'cube-link-btn';
        btn.setAttribute('data-collapse-all', '');
        btn.textContent = 'Collapse all';
        btn.addEventListener('click', function () {
            const sections = container.querySelectorAll('details.cube-pack, details.cube-group');
            const anyOpen = Array.prototype.some.call(sections, function (d) { return d.open; });
            Array.prototype.forEach.call(sections, function (d) { d.open = !anyOpen; });
            btn.textContent = anyOpen ? 'Expand all' : 'Collapse all';
        });
        bar.appendChild(btn);
        return bar;
    }

    /** The preview: each colour/land group with its as-fan AND its cards. */
    function renderGroups(container, groups) {
        container.replaceChildren();
        if (groups.length > 1) container.appendChild(collapseToggle(container));
        const max = groups.reduce(function (m, g) { return Math.max(m, g.asFan); }, 0);
        groups.forEach(function (group) {
            const block = document.createElement('details');
            block.className = 'cube-group';
            block.open = true;

            const summary = document.createElement('summary');
            summary.className = 'cube-section-summary';
            summary.appendChild(collapseChevron());
            const head = document.createElement('div');
            head.className = 'cube-group-head';
            const label = document.createElement('span');
            label.className = 'cube-bar-label';
            const swatch = document.createElement('span');
            swatch.className = 'cube-swatch';
            swatch.style.background = categoryColor(group.category);
            label.appendChild(swatch);
            label.appendChild(document.createTextNode(group.category + ' (' + group.count + ')'));
            const value = document.createElement('span');
            value.className = 'cube-bar-value';
            value.textContent = formatAsFan(group.asFan) + ' / pack';
            head.appendChild(label);
            head.appendChild(asFanBar(group.category, group.asFan, max));
            head.appendChild(value);
            summary.appendChild(head);
            block.appendChild(summary);

            const grid = document.createElement('div');
            grid.className = 'cube-card-grid';
            group.cards.forEach(function (card) {
                grid.appendChild(cardTile(card));
            });
            block.appendChild(grid);
            container.appendChild(block);
        });
        return container;
    }

    function renderPacks(container, packs) {
        container.replaceChildren();
        if (packs.length > 1) container.appendChild(collapseToggle(container));
        packs.forEach(function (pack, i) {
            const card = document.createElement('details');
            card.className = 'cube-pack';
            card.open = true;
            const summary = document.createElement('summary');
            summary.className = 'cube-section-summary cube-pack-head';
            summary.appendChild(collapseChevron());
            const heading = document.createElement('h3');
            heading.textContent = 'Pack ' + (i + 1);
            const count = document.createElement('span');
            count.className = 'cube-pack-count';
            count.textContent = pack.length + ' cards';
            heading.appendChild(count);
            summary.appendChild(heading);
            card.appendChild(summary);
            const grid = document.createElement('div');
            grid.className = 'cube-card-grid';
            pack.forEach(function (c) {
                grid.appendChild(cardTile(c));
            });
            card.appendChild(grid);
            container.appendChild(card);
        });
        return container;
    }

    function setStatus(el, msg) {
        if (el) el.textContent = msg;
    }

    /** True on touch / no-hover devices, where tap-to-enlarge replaces hover. */
    function prefersTap() {
        return !!(root && root.matchMedia && root.matchMedia('(hover: none)').matches);
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

    function show(el) { if (el) el.hidden = false; }
    function hide(el) { if (el) el.hidden = true; }

    // --- DOM wiring (browser only) -------------------------------------

    function q(doc, sel) { return doc.querySelector(sel); }
    function statusFor(doc, name) { return q(doc, '[data-status="' + name + '"]'); }
    function emptyFor(doc, name) { return q(doc, '[data-empty="' + name + '"]'); }

    function activateTab(doc, name) {
        if (TABS.indexOf(name) < 0) return;
        doc.querySelectorAll('[role="tab"][data-tab]').forEach(function (tab) {
            const selected = tab.getAttribute('data-tab') === name;
            tab.setAttribute('aria-selected', selected ? 'true' : 'false');
            tab.tabIndex = selected ? 0 : -1;
        });
        doc.querySelectorAll('[data-panel]').forEach(function (panel) {
            panel.hidden = panel.getAttribute('data-panel') !== name;
        });
        // Some tools (as-fan, compare) work off their own inputs, so the shared
        // "Your cube" source (and its cue) don't apply — hide them there.
        const usesSource = NO_SHARED_SOURCE.indexOf(name) < 0;
        doc.querySelectorAll('[data-needs-cube]').forEach(function (el) {
            el.hidden = !usesSource;
        });
    }

    function wireTabs(doc) {
        const tabs = Array.prototype.slice.call(doc.querySelectorAll('[role="tab"][data-tab]'));
        if (!tabs.length) return;
        tabs.forEach(function (tab, i) {
            tab.addEventListener('click', function () {
                const name = tab.getAttribute('data-tab');
                activateTab(doc, name);
                if (root.history && root.history.replaceState) {
                    root.history.replaceState(null, '', '#' + name);
                }
            });
            tab.addEventListener('keydown', function (e) {
                let next = -1;
                if (e.key === 'ArrowRight') next = (i + 1) % tabs.length;
                else if (e.key === 'ArrowLeft') next = (i - 1 + tabs.length) % tabs.length;
                if (next >= 0) {
                    e.preventDefault();
                    tabs[next].focus();
                    tabs[next].click();
                }
            });
        });
        const fromHash = tabIdFromHash(root.location && root.location.hash);
        if (fromHash) activateTab(doc, fromHash);
    }

    /**
     * Re-activate the matching tab when only the URL hash changes — i.e. a
     * deep-link like /cube#preview clicked while already on /cube (e.g. from
     * the navbar dropdown), where no reload fires wireTabs again. Registered
     * unconditionally so it's live even if wireTabs bailed for lack of tabs.
     */
    function wireHashChange(doc) {
        if (!root || !root.addEventListener) return;
        root.addEventListener('hashchange', function () {
            const name = tabIdFromHash(root.location && root.location.hash);
            if (name) activateTab(doc, name);
        });
    }

    function wireExamples(doc) {
        doc.querySelectorAll('[data-example]').forEach(function (chip) {
            chip.addEventListener('click', function () {
                const value = chip.getAttribute('data-example');
                doc.querySelectorAll('input[name="query"]').forEach(function (input) {
                    input.value = value;
                });
            });
        });
    }

    function getJson(url) {
        return fetch(url).then(function (r) { return r.json(); });
    }

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        }).then(function (r) { return r.json(); });
    }

    // --- Deep links, card count, in-progress spinner -------------------

    /** A shareable URL that pre-loads a Scryfall query (no login needed). */
    function queryShareUrl(origin, query) {
        return (origin || '') + '/cube?q=' + encodeURIComponent(query);
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

    function setSpinner(doc, name, on) {
        const el = doc.querySelector('[data-spinner="' + name + '"]');
        if (el) el.hidden = !on;
    }

    /** Live "N cards" counter + a clear button for the paste box. */
    function wireCardCount(doc) {
        const textarea = doc.querySelector('textarea[name="list"]');
        const count = doc.querySelector('[data-card-count]');
        if (!textarea || !count) return;
        function update() {
            const n = countCards(textarea.value);
            count.textContent = n + (n === 1 ? ' card' : ' cards');
        }
        textarea.addEventListener('input', update);
        update(); // initial (a shared/prefilled list arrives populated)
        const clear = doc.querySelector('[data-clear-list]');
        if (clear) clear.addEventListener('click', function () {
            textarea.value = '';
            update();
            textarea.focus();
        });
    }

    /** "Copy link" for the current Scryfall search → /cube?q=... */
    function wireCopyQuery(doc) {
        const btn = doc.querySelector('[data-copy-query]');
        const input = doc.querySelector('[data-source-panel="search"] input[name="query"]');
        if (!btn || !input) return;
        const label = btn.textContent;
        btn.addEventListener('click', function () {
            const q = (input.value || '').trim();
            if (!q) { btn.textContent = 'Enter a search first'; root.setTimeout(function () { btn.textContent = label; }, 1500); return; }
            const url = queryShareUrl(root.location && root.location.origin, q);
            if (root.navigator && root.navigator.clipboard) {
                root.navigator.clipboard.writeText(url).then(
                    function () { btn.textContent = '✓ Link copied'; root.setTimeout(function () { btn.textContent = label; }, 2000); },
                    function () { btn.textContent = url; }
                );
            } else {
                btn.textContent = url;
            }
        });
    }

    /** Pre-loads the source from ?q= / ?list= so a shared link lands ready. */
    function prefillFromUrl(doc) {
        const pre = readUrlPrefill(root.location && root.location.search);
        if (pre.q != null) {
            const input = doc.querySelector('[data-source-panel="search"] input[name="query"]');
            if (input) input.value = pre.q;
            setSource(doc, 'search');
        } else if (pre.list != null) {
            const textarea = doc.querySelector('textarea[name="list"]');
            if (textarea) textarea.value = pre.list;
            setSource(doc, 'list');
        }
    }

    /** Disables the form's submit button while a request is in flight. */
    function withBusy(form, busy) {
        const btn = form.querySelector('button[type="submit"]');
        if (btn) btn.disabled = busy;
    }

    // --- Saved lists (account-bound; persisted server-side) ------------

    const LISTS_API = '/cube/api/lists';

    /** DELETE URL for a saved list, with the name safely encoded. */
    function deleteListUrl(name) {
        return LISTS_API + '?name=' + encodeURIComponent(name);
    }

    /** Joins the page origin with a server-returned relative share path. */
    function absoluteUrl(origin, path) {
        return (origin || '') + path;
    }

    /** Renders the "couldn't find these names" warning after a list lookup. */
    function renderNotFound(el, names) {
        if (!el) return;
        if (!names || !names.length) {
            el.hidden = true;
            el.textContent = '';
            return;
        }
        el.textContent = 'Couldn’t find ' + names.length + ' card' + (names.length > 1 ? 's' : '') +
            ': ' + names.join(', ');
        el.hidden = false;
    }

    // --- Card source (Scryfall search vs pasted list) ------------------

    /** Which source is active and its value. */
    function activeSource(doc) {
        const listTab = doc.querySelector('[data-source-tab="list"]');
        if (listTab && listTab.getAttribute('aria-selected') === 'true') {
            const ta = doc.querySelector('[data-source-panel="list"] textarea[name="list"]');
            return { mode: 'list', list: ((ta && ta.value) || '').trim() };
        }
        const input = doc.querySelector('[data-source-panel="search"] input[name="query"]');
        return { mode: 'search', query: ((input && input.value) || '').trim() };
    }

    function setSource(doc, mode) {
        doc.querySelectorAll('[data-source-tab]').forEach(function (b) {
            b.setAttribute('aria-selected', b.getAttribute('data-source-tab') === mode ? 'true' : 'false');
        });
        doc.querySelectorAll('[data-source-panel]').forEach(function (p) {
            p.hidden = p.getAttribute('data-source-panel') !== mode;
        });
    }

    function wireSource(doc) {
        doc.querySelectorAll('[data-source-tab]').forEach(function (btn) {
            btn.addEventListener('click', function () { setSource(doc, btn.getAttribute('data-source-tab')); });
        });
    }

    /** Repopulates the saved-cube datalist(s) from the cached {name: cards} map. */
    function fillSavedOptions(doc, cache) {
        const names = Object.keys(cache).sort();
        doc.querySelectorAll('[data-saved-options]').forEach(function (dl) {
            dl.replaceChildren();
            names.forEach(function (name) {
                const opt = document.createElement('option');
                opt.value = name;
                dl.appendChild(opt);
            });
        });
    }

    /**
     * Account-bound saved lists: persisted server-side against the logged-in
     * Discord user via `/cube/api/lists`, so they follow the user across
     * devices. The saved-cube picker is a datalist-backed typeahead — type to
     * filter your cube names, pick one to load it. The controls only exist in
     * the DOM for a logged-in user (see cube.html), so this no-ops otherwise.
     */
    function wireSavedLists(doc) {
        const textarea = doc.querySelector('textarea[name="list"]');
        const input = doc.querySelector('[data-saved-lists]');
        if (!textarea || !input) return;
        const saveBtn = doc.querySelector('[data-save-list]');
        const delBtn = doc.querySelector('[data-delete-list]');
        const status = doc.querySelector('[data-saved-status]');
        let cache = {};

        function reload() {
            return getJson(LISTS_API)
                .then(function (rows) {
                    cache = {};
                    (rows || []).forEach(function (row) { cache[row.name] = row.cards; });
                    fillSavedOptions(doc, cache);
                })
                .catch(function () { /* leave options as-is on a transient error */ });
        }

        reload();

        // Typeahead: picking (or typing) a known saved name loads that cube.
        input.addEventListener('change', function () {
            const name = (input.value || '').trim();
            if (cache[name] == null) return;
            textarea.value = cache[name];
            setSource(doc, 'list');
            setStatus(status, 'Loaded “' + name + '”.');
        });

        if (saveBtn) saveBtn.addEventListener('click', function () {
            const text = (textarea.value || '').trim();
            if (!text) { setStatus(status, 'Nothing to save — paste a list first.'); return; }
            // Default the prompt to the name in the picker so editing and
            // re-saving overwrites it in one step (Enter); a new name forks it.
            const suggested = (input.value || '').trim();
            const name = root.prompt ? root.prompt('Name this cube list (same name overwrites):', suggested) : null;
            if (!name || !name.trim()) return;
            setStatus(status, 'Saving…');
            fetch(LISTS_API, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name.trim(), cards: text }),
            }).then(function (r) {
                if (r.status === 409) { setStatus(status, 'You\'ve hit the saved-list limit (50).'); return null; }
                if (!r.ok) { setStatus(status, 'Couldn\'t save. Are you still logged in?'); return null; }
                return r.json();
            }).then(function (saved) {
                if (!saved) return;
                setStatus(status, 'Saved “' + saved.name + '”.');
                reload().then(function () { input.value = saved.name; });
            }).catch(function () { setStatus(status, 'Couldn\'t save. Try again.'); });
        });

        if (delBtn) delBtn.addEventListener('click', function () {
            const name = (input.value || '').trim();
            if (!name) return;
            setStatus(status, 'Deleting…');
            fetch(deleteListUrl(name), { method: 'DELETE' })
                .then(function () { setStatus(status, 'Deleted “' + name + '”.'); input.value = ''; return reload(); })
                .catch(function () { setStatus(status, 'Couldn\'t delete. Try again.'); });
        });
    }

    /**
     * Publishes the current list as a public, immutable snapshot (logged-in
     * only) and shows the resulting `/cube/c/<token>` link, copying it to the
     * clipboard. The Share controls only exist in the DOM for a logged-in user.
     */
    function wireShare(doc) {
        const textarea = doc.querySelector('textarea[name="list"]');
        const shareBtn = doc.querySelector('[data-share-list]');
        if (!textarea || !shareBtn) return;
        const result = doc.querySelector('[data-share-result]');
        const urlInput = doc.querySelector('[data-share-url]');
        const copyBtn = doc.querySelector('[data-share-copy]');
        const status = doc.querySelector('[data-saved-status]');
        const nameInput = doc.querySelector('[data-saved-lists]');

        function copy(text) {
            if (root.navigator && root.navigator.clipboard) {
                root.navigator.clipboard.writeText(text).then(
                    function () { setStatus(status, 'Share link copied to clipboard.'); },
                    function () { /* clipboard blocked — the field is selectable */ }
                );
            }
        }

        shareBtn.addEventListener('click', function () {
            const text = (textarea.value || '').trim();
            if (!text) { setStatus(status, 'Nothing to share — paste a list first.'); return; }
            const name = ((nameInput && nameInput.value) || '').trim();
            setStatus(status, 'Creating link…');
            fetch('/cube/api/share', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name, cards: text }),
            }).then(function (r) {
                if (r.status === 401) { setStatus(status, 'Log in to create a share link.'); return null; }
                if (!r.ok) { setStatus(status, 'Couldn\'t create a link. Try again.'); return null; }
                return r.json();
            }).then(function (json) {
                if (!json) return;
                const full = absoluteUrl(root.location && root.location.origin, json.url);
                if (urlInput) urlInput.value = full;
                if (result) result.hidden = false;
                setStatus(status, 'Share link ready.');
                copy(full);
            }).catch(function () { setStatus(status, 'Couldn\'t create a link. Try again.'); });
        });

        if (copyBtn && urlInput) copyBtn.addEventListener('click', function () {
            urlInput.select();
            copy(urlInput.value);
        });
    }

    // --- Card-name autocomplete (Scryfall) -----------------------------

    const MAX_CARD_SUGGEST = 10;

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

    /**
     * As the user types a card name in the list box, suggests real card names
     * from Scryfall's autocomplete API and completes the current line on pick
     * (keeping any leading quantity). Arrow keys + Enter/Escape, plus mouse.
     */
    function wireCardAutocomplete(doc) {
        const textarea = doc.querySelector('textarea[name="list"]');
        const box = doc.querySelector('[data-card-suggest]');
        if (!textarea || !box) return;
        let timer = null;
        let controller = null;
        let items = [];
        let active = -1;

        function close() { box.hidden = true; box.replaceChildren(); items = []; active = -1; }

        function setActive(i) {
            const lis = box.querySelectorAll('.cube-card-suggest-item');
            lis.forEach(function (el) { el.classList.remove('is-active'); });
            if (i < 0 || i >= lis.length) { active = -1; return; }
            active = i;
            lis[i].classList.add('is-active');
            lis[i].scrollIntoView({ block: 'nearest' });
        }

        function pick(name) {
            const applied = applyCardChoice(textarea.value, textarea.selectionStart, name);
            textarea.value = applied.value;
            textarea.selectionStart = textarea.selectionEnd = applied.caret;
            close();
            textarea.focus();
        }

        function render(names) {
            box.replaceChildren();
            items = names;
            active = -1;
            if (!names.length) { close(); return; }
            names.forEach(function (name) {
                const li = document.createElement('li');
                li.className = 'cube-card-suggest-item';
                li.setAttribute('role', 'option');
                li.textContent = name;
                li.addEventListener('mousedown', function (e) { e.preventDefault(); pick(name); });
                box.appendChild(li);
            });
            box.hidden = false;
        }

        function suggest() {
            const info = currentLineInfo(textarea.value, textarea.selectionStart);
            const partial = splitQuantityPrefix(info.text).name.trim();
            if (partial.length < 2) { close(); return; }
            if (controller) controller.abort();
            controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
            fetch(scryfallAutocompleteUrl(partial), controller ? { signal: controller.signal } : undefined)
                .then(function (r) { return r.ok ? r.json() : { data: [] }; })
                .then(function (json) { render((json.data || []).slice(0, MAX_CARD_SUGGEST)); })
                .catch(function () { /* aborted or offline — leave the box as it is */ });
        }

        textarea.addEventListener('input', function () {
            if (timer) clearTimeout(timer);
            timer = setTimeout(suggest, 160);
        });
        textarea.addEventListener('keydown', function (e) {
            if (box.hidden) return;
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive(Math.min(active + 1, items.length - 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(Math.max(active - 1, 0)); }
            else if (e.key === 'Enter' && active >= 0) { e.preventDefault(); pick(items[active]); }
            else if (e.key === 'Escape') { close(); }
        });
        textarea.addEventListener('blur', function () { setTimeout(close, 150); });
    }

    function wireCardLookup(doc) {
        const form = q(doc, '[data-form="card"]');
        if (!form) return;
        const status = statusFor(doc, 'card');
        const result = q(doc, '[data-result="card"]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const name = (new FormData(form).get('name') || '').trim();
            if (!name) { setStatus(status, 'Enter a card name.'); return; }
            setStatus(status, 'Looking up…');
            hide(result);
            withBusy(form, true);
            getJson(cardUrl(name))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'No card found.'); return; }
                    setStatus(status, '');
                    renderCardLookup(result, json.card);
                    show(result);
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    function wireCompare(doc) {
        const form = q(doc, '[data-form="compare"]');
        if (!form) return;
        const status = statusFor(doc, 'compare');
        const result = q(doc, '[data-result="compare"]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const listA = (data.get('listA') || '').trim();
            const listB = (data.get('listB') || '').trim();
            if (!listA && !listB) { setStatus(status, 'Paste a card list into both sides to compare.'); return; }
            setStatus(status, 'Comparing…');
            hide(result);
            withBusy(form, true);
            postJson('/cube/api/diff', { listA: listA, listB: listB })
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not compare.'); return; }
                    setStatus(status, 'List A: ' + json.sizeA + ' cards · List B: ' + json.sizeB + ' cards');
                    renderDiff(result, json);
                    show(result);
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    function wireAsFan(doc) {
        const form = q(doc, '[data-form="asfan"]');
        if (!form) return;
        const status = statusFor(doc, 'asfan');
        const result = q(doc, '[data-result="asfan"]');
        const number = q(doc, '[data-asfan-number]');
        const sentence = q(doc, '[data-asfan-sentence]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const params = { total: data.get('total'), cubeSize: data.get('cubeSize'), packSize: data.get('packSize') };
            setStatus(status, 'Calculating…');
            hide(result);
            withBusy(form, true);
            getJson(asfanUrl(params))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Invalid inputs.'); return; }
                    setStatus(status, '');
                    number.textContent = formatAsFan(json.value);
                    if (sentence) sentence.textContent = asfanSentence(json.value, Number(params.packSize));
                    show(result);
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    /** Validates the active source; returns an error string or null. */
    function sourceError(src) {
        if (src.mode === 'list') return src.list ? null : 'Paste a card list first (under "Your cube").';
        return src.query ? null : 'Enter a Scryfall search first (under "Your cube").';
    }

    function sourceLabel(src) {
        return src.mode === 'list' ? 'Your list' : src.query;
    }

    function wirePreview(doc) {
        const form = q(doc, '[data-form="preview"]');
        if (!form) return;
        const status = statusFor(doc, 'preview');
        const empty = emptyFor(doc, 'preview');
        const groups = q(doc, '[data-result="preview"]');
        const notFound = q(doc, '[data-notfound="preview"]');
        const dupes = q(doc, '[data-duplicates="preview"]');
        const breakdown = q(doc, '[data-breakdown="preview-analytics"]');
        const avgMv = q(doc, '[data-avg-mv="preview"]');
        const curve = q(doc, '[data-curve="preview"]');
        const types = q(doc, '[data-types="preview"]');
        const rarity = q(doc, '[data-rarity="preview"]');
        const pairs = q(doc, '[data-pairs="preview"]');
        const pips = q(doc, '[data-pips="preview"]');
        const actions = q(doc, '[data-actions="preview"]');
        const copyBtn = q(doc, '[data-copy="preview"]');
        const downloadBtn = q(doc, '[data-download="preview"]');
        const sampleBtn = q(doc, '[data-sample-pack="preview"]');
        const sample = q(doc, '[data-sample="preview"]');
        let lastGroups = [];

        if (copyBtn) {
            const label = copyBtn.textContent;
            copyBtn.addEventListener('click', function () {
                if (!lastGroups.length) return;
                const text = cubeListText(lastGroups);
                if (root.navigator && root.navigator.clipboard) {
                    root.navigator.clipboard.writeText(text).then(
                        function () { copyBtn.textContent = '✓ Copied'; root.setTimeout(function () { copyBtn.textContent = label; }, 2000); },
                        function () { /* clipboard blocked — the download still works */ }
                    );
                }
            });
        }
        if (downloadBtn) {
            downloadBtn.addEventListener('click', function () {
                if (!lastGroups.length) return;
                const blob = new Blob([cubeListText(lastGroups)], { type: 'text/plain' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'cube.txt';
                a.click();
                URL.revokeObjectURL(url);
            });
        }

        if (sampleBtn) {
            sampleBtn.addEventListener('click', function () {
                const src = activeSource(doc);
                const srcErr = sourceError(src);
                if (srcErr) { setStatus(status, srcErr); return; }
                const req = samplePackRequest(src, new FormData(form).get('packSize'));
                sampleBtn.disabled = true;
                if (sample) hide(sample);
                const pending = req.method === 'POST' ? postJson(req.url, req.body) : getJson(req.url);
                pending
                    .then(function (json) {
                        if (!json.ok) { setStatus(status, json.error || 'Could not deal a pack.'); return; }
                        renderPacks(sample, json.packs);
                        show(sample);
                    })
                    .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                    .then(function () { sampleBtn.disabled = false; });
            });
        }

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const packSize = new FormData(form).get('packSize');
            const src = activeSource(doc);
            const err = sourceError(src);
            if (err) { setStatus(status, err); return; }
            setStatus(status, src.mode === 'list' ? 'Resolving your list…' : 'Fetching cards from Scryfall…');
            hide(groups); hide(notFound); hide(dupes); hide(breakdown); hide(actions); hide(sample);
            withBusy(form, true);
            setSpinner(doc, 'preview', true);
            const request = src.mode === 'list'
                ? postJson('/cube/api/preview', { list: src.list, packSize: Number(packSize) })
                : getJson(previewUrl({ query: src.query, packSize: packSize }));
            request
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'No results.'); return; }
                    setStatus(status, sourceLabel(src) + ' → ' + json.poolSize + ' cards' + (json.note ? ' · ' + json.note : ''));
                    hide(empty);
                    renderGroups(groups, json.groups);
                    show(groups);
                    lastGroups = json.groups || [];
                    show(actions);
                    renderNotFound(notFound, json.notFound);
                    renderAnalytics(json.analytics, { dupes: dupes, breakdown: breakdown, avgMv: avgMv, curve: curve, types: types, rarity: rarity, pairs: pairs, pips: pips });
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); })
                .then(function () { withBusy(form, false); setSpinner(doc, 'preview', false); });
        });
    }

    function wireGenerate(doc) {
        const form = q(doc, '[data-form="generate"]');
        if (!form) return;
        const status = statusFor(doc, 'generate');
        const empty = emptyFor(doc, 'generate');
        const summary = q(doc, '[data-summary="generate"]');
        const actions = q(doc, '[data-actions="generate"]');
        const breakdown = q(doc, '[data-breakdown="generate"]');
        const dist = q(doc, '[data-dist="generate"]');
        const result = q(doc, '[data-result="generate"]');
        const notFound = q(doc, '[data-notfound="generate"]');
        const downloadBtn = q(doc, '[data-download="generate"]');
        let lastPacks = [];

        if (downloadBtn) {
            downloadBtn.addEventListener('click', function () {
                if (!lastPacks.length) return;
                const blob = new Blob([packsToText(lastPacks)], { type: 'text/plain' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'cube-packs.txt';
                a.click();
                URL.revokeObjectURL(url);
            });
        }

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const packs = data.get('packs');
            const packSize = data.get('packSize');
            const balanced = form.querySelector('[name="balanced"]').checked;
            const src = activeSource(doc);
            const err = sourceError(src);
            if (err) { setStatus(status, err); return; }
            setStatus(status, src.mode === 'list' ? 'Resolving your list and dealing packs…' : 'Drawing cards and dealing packs…');
            hide(summary); hide(actions); hide(breakdown); hide(result); hide(notFound);
            withBusy(form, true);
            setSpinner(doc, 'generate', true);
            const request = src.mode === 'list'
                ? postJson('/cube/api/generate', { list: src.list, packs: Number(packs), packSize: Number(packSize), balanced: balanced })
                : getJson(generateUrl({ query: src.query, packs: packs, packSize: packSize, balanced: balanced }));
            request
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not build packs.'); return; }
                    setStatus(status, '');
                    hide(empty);
                    lastPacks = json.packs;
                    summary.textContent = 'Dealt ' + json.packCount + ' packs of ' + json.packSize +
                        ' from a ' + json.poolSize + '-card pool. Click any card to view it on Scryfall.' +
                        (json.note ? ' ' + json.note : '');
                    show(summary);
                    renderPacks(result, json.packs);
                    show(result);
                    show(actions);
                    renderDistribution(dist, json.distribution);
                    show(breakdown);
                    renderNotFound(notFound, json.notFound);
                })
                .catch(function () { setStatus(status, 'Something went wrong building packs.'); })
                .then(function () { withBusy(form, false); setSpinner(doc, 'generate', false); });
        });
    }

    /**
     * Hover-to-enlarge: one shared floating image, shown while the pointer
     * is over any card tile that carries a `data-large` URL, positioned
     * next to the cursor. Event-delegated on the document so it covers
     * tiles rendered after load. Hard-off on touch via CSS (`hover: none`).
     */
    function wireZoom(doc) {
        if (!doc.body) return;
        const overlay = doc.createElement('figure');
        overlay.className = 'cube-zoom';
        overlay.hidden = true;
        overlay.setAttribute('aria-hidden', 'true');
        const overlayImg = doc.createElement('img');
        overlayImg.className = 'cube-zoom-img';
        overlayImg.alt = '';
        const overlayStat = doc.createElement('figcaption');
        overlayStat.className = 'cube-zoom-stat';
        overlay.appendChild(overlayImg);
        overlay.appendChild(overlayStat);
        doc.body.appendChild(overlay);

        const view = doc.defaultView || { innerWidth: 1024, innerHeight: 768 };

        function place(e) {
            const w = overlay.offsetWidth || 240;
            const h = overlay.offsetHeight || 360;
            const pos = zoomPosition(e.clientX, e.clientY, w, h, view.innerWidth, view.innerHeight);
            overlay.style.left = pos.left + 'px';
            overlay.style.top = pos.top + 'px';
        }

        function cardFrom(node) {
            return node && node.closest ? node.closest('.cube-card[data-large]') : null;
        }

        doc.addEventListener('mouseover', function (e) {
            const card = cardFrom(e.target);
            if (!card) return;
            overlayImg.src = card.getAttribute('data-large');
            const stat = card.getAttribute('data-statline') || '';
            overlayStat.textContent = stat;
            overlayStat.hidden = stat === '';
            overlay.hidden = false;
            place(e);
        });
        doc.addEventListener('mousemove', function (e) {
            if (!overlay.hidden) place(e);
        });
        doc.addEventListener('mouseout', function (e) {
            const card = cardFrom(e.target);
            if (!card) return;
            // Ignore moves between the card's own children.
            if (cardFrom(e.relatedTarget) === card) return;
            overlay.hidden = true;
            overlayImg.removeAttribute('src');
        });
    }

    /**
     * Tap-to-enlarge for touch / no-hover devices (the mobile counterpart
     * to wireZoom). Tapping a card opens a centered lightbox with the
     * full-size image, stat line, and a "View on Scryfall" link, instead
     * of navigating straight off the page. Dismissed by the close button,
     * a backdrop tap, or Escape. On hover devices taps fall through to the
     * normal link.
     */
    function wireLightbox(doc) {
        if (!doc.body) return;
        const modal = doc.createElement('div');
        modal.className = 'cube-lightbox';
        modal.hidden = true;
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-label', 'Card preview');

        const closeBtn = doc.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'cube-lightbox-close';
        closeBtn.setAttribute('aria-label', 'Close');
        closeBtn.textContent = '✕';

        const figure = doc.createElement('figure');
        figure.className = 'cube-lightbox-card';
        const img = doc.createElement('img');
        img.className = 'cube-lightbox-img';
        img.alt = '';
        const stat = doc.createElement('figcaption');
        stat.className = 'cube-lightbox-stat';
        const link = doc.createElement('a');
        link.className = 'btn btn-secondary cube-lightbox-link';
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = 'View on Scryfall ↗';
        figure.appendChild(img);
        figure.appendChild(stat);
        figure.appendChild(link);
        modal.appendChild(closeBtn);
        modal.appendChild(figure);
        doc.body.appendChild(modal);

        function open(card) {
            img.src = card.getAttribute('data-large');
            const line = card.getAttribute('data-statline') || '';
            stat.textContent = line;
            stat.hidden = line === '';
            link.href = card.getAttribute('href') || '#';
            modal.hidden = false;
        }
        function close() {
            modal.hidden = true;
            img.removeAttribute('src');
        }

        doc.addEventListener('click', function (e) {
            // A flip-button tap is handled by wireCardFlip, not the lightbox.
            if (e.target.closest && e.target.closest('.cube-card-flip')) return;
            const card = e.target.closest && e.target.closest('.cube-card[data-large]');
            if (card && prefersTap()) {
                e.preventDefault();
                open(card);
            }
        });
        closeBtn.addEventListener('click', close);
        modal.addEventListener('click', function (e) {
            if (e.target === modal) close(); // backdrop, not the figure
        });
        doc.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !modal.hidden) close();
        });
    }

    /**
     * Flips a double-faced card's tile in place: swaps the thumbnail image and
     * the `data-large` URL (which drives the hover-zoom and lightbox) between
     * the front and back faces. Event-delegated so it covers tiles rendered
     * after load. preventDefault stops the tile's Scryfall link from firing.
     */
    function wireCardFlip(doc) {
        doc.addEventListener('click', function (e) {
            const flip = e.target.closest && e.target.closest('.cube-card-flip');
            if (!flip) return;
            e.preventDefault();
            e.stopPropagation();
            const card = flip.closest('.cube-card');
            if (!card || !card.getAttribute('data-back')) return;
            const img = card.querySelector('img.cube-card-img');
            const flipped = card.getAttribute('data-flipped') === 'true';
            if (flipped) {
                card.setAttribute('data-large', card.getAttribute('data-front-large') || '');
                if (img) img.src = card.getAttribute('data-front-thumb') || img.src;
                card.setAttribute('data-flipped', 'false');
            } else {
                const back = card.getAttribute('data-back');
                card.setAttribute('data-large', back);
                if (img) img.src = back;
                card.setAttribute('data-flipped', 'true');
            }
        });
    }

    function wire(doc) {
        wireTabs(doc);
        wireHashChange(doc);
        wireSource(doc);
        wireSavedLists(doc);
        wireShare(doc);
        wireCardAutocomplete(doc);
        prefillFromUrl(doc);
        // A shared cube arrives pre-loaded in the list box — show that source
        // (this wins over a ?q= / ?list= prefill).
        if (doc.querySelector('[data-shared-banner]')) setSource(doc, 'list');
        wireCardCount(doc);
        wireCopyQuery(doc);
        wireExamples(doc);
        wireAsFan(doc);
        wireCompare(doc);
        wireCardLookup(doc);
        wirePreview(doc);
        wireGenerate(doc);
        wireCardFlip(doc);
        wireZoom(doc);
        wireLightbox(doc);
    }

    if (root && root.document) wire(root.document);

    const api = {
        formatAsFan: formatAsFan,
        asfanSentence: asfanSentence,
        categoryColor: categoryColor,
        scryfallCardUrl: scryfallCardUrl,
        cardStatline: cardStatline,
        manaSymbolUrls: manaSymbolUrls,
        tabIdFromHash: tabIdFromHash,
        zoomPosition: zoomPosition,
        deleteListUrl: deleteListUrl,
        absoluteUrl: absoluteUrl,
        queryShareUrl: queryShareUrl,
        readUrlPrefill: readUrlPrefill,
        countCards: countCards,
        scryfallAutocompleteUrl: scryfallAutocompleteUrl,
        currentLineInfo: currentLineInfo,
        splitQuantityPrefix: splitQuantityPrefix,
        applyCardChoice: applyCardChoice,
        packsToText: packsToText,
        groupsToList: groupsToList,
        cubeListText: cubeListText,
        asfanUrl: asfanUrl,
        previewUrl: previewUrl,
        generateUrl: generateUrl,
        samplePackRequest: samplePackRequest,
        renderDistribution: renderDistribution,
        renderDiff: renderDiff,
        cardUrl: cardUrl,
        renderCardLookup: renderCardLookup,
        renderManaCurve: renderManaCurve,
        renderTypeBreakdown: renderTypeBreakdown,
        renderRarity: renderRarity,
        renderColorPairs: renderColorPairs,
        renderColorPips: renderColorPips,
        renderDuplicates: renderDuplicates,
        renderAnalytics: renderAnalytics,
        renderGroups: renderGroups,
        renderPacks: renderPacks,
    };
    if (root) root.TobyCube = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
