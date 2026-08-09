// Magic toolkit (/magic), layer 2 of 3: the renderers — functions that turn
// an API response into DOM. They build elements and return them; they never
// fetch, and they hold no page state, which is what keeps them testable
// against a bare jsdom document.
(function (root) {
    'use strict';

    // Resolved from the layer below: `require` under Jest, the shared global
    // in the browser (the page loads these files in dependency order).
    const fmt = (typeof module !== 'undefined' && module.exports)
        ? require('./magic-format.js')
        : root.TobyCubeFormat;

    // Hoisted into scope so the code below reads exactly as it did when
    // this all lived in one file.
    const NEUTRAL_BAR = fmt.NEUTRAL_BAR;
    const cardCountLabel = fmt.cardCountLabel;
    const cardStatline = fmt.cardStatline;
    const categoryColor = fmt.categoryColor;
    const formatAsFan = fmt.formatAsFan;
    const formatMoney = fmt.formatMoney;
    const manaSymbolUrls = fmt.manaSymbolUrls;
    const notFoundText = fmt.notFoundText;
    const priceLine = fmt.priceLine;
    const scryfallCardUrl = fmt.scryfallCardUrl;
    const totalValueText = fmt.totalValueText;
    const watchLine = fmt.watchLine;

    /**
     * Points the generate form's "how many packs / cards per pack" boxes at a
     * loaded deal's shape, clamped to what the inputs accept. Returns the
     * values applied so it's unit-testable.
     */
    function applyPackShape(form, json) {
        const applied = {};
        [['packs', json.packCount], ['packSize', json.packSize]].forEach(function (pair) {
            const input = form && form.querySelector('[name="' + pair[0] + '"]');
            const value = Number(pair[1]);
            if (!input || !value || value < 1) return;
            const max = Number(input.max);
            applied[pair[0]] = max > 0 ? Math.min(value, max) : value;
            input.value = applied[pair[0]];
        });
        return applied;
    }

    /** Saves [text] to the user's downloads as [filename]. */
    function downloadText(filename, text) {
        const blob = new Blob([text], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        URL.revokeObjectURL(url);
    }

    /** Reads a picked file as text. */
    function readFileText(file) {
        return new Promise(function (resolve, reject) {
            const reader = new FileReader();
            reader.onload = function () { resolve(String(reader.result || '')); };
            reader.onerror = function () { reject(reader.error); };
            reader.readAsText(file);
        });
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
        // A reliable name hook for click-through (the search grid opens the
        // in-page detail panel; the href stays as a Scryfall fallback).
        a.setAttribute('data-card-name', card.name);
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
        const totals = analytics.totalValues || [];
        const code = (els.valueCurrency && els.valueCurrency.value) || 'usd';
        renderTotalValue(els.value, totals, code);
        renderValueExtremes(els.extremes, analytics.valueExtremes || [], code);
        // Only offer the currency switch when something in the pool is priced.
        if (els.valueCurrency) els.valueCurrency.hidden = totals.length === 0;
        show(els.breakdown);
    }

    /** Renders the cube's total value in the [code] currency; hides when nothing is priced in it. */
    function renderTotalValue(el, totalValues, code) {
        if (!el) return;
        const text = totalValueText(totalValues, code);
        if (!text) { el.hidden = true; el.textContent = ''; return; }
        el.textContent = text;
        el.hidden = false;
    }

    /** Renders the most/least valuable cards in [code] as two rows; hides when none priced. */
    function renderValueExtremes(el, valueExtremes, code) {
        if (!el) return;
        const match = (valueExtremes || []).filter(function (e) { return e.currency === code; })[0];
        if (!match) { el.hidden = true; el.replaceChildren(); return; }
        el.replaceChildren();
        function row(label, name, amount) {
            const div = document.createElement('div');
            div.className = 'cube-extreme';
            const tag = document.createElement('span');
            tag.className = 'cube-extreme-label';
            tag.textContent = label;
            const card = document.createElement('span');
            card.className = 'cube-extreme-card';
            card.textContent = name + ' (' + formatMoney(amount, code) + ')';
            div.appendChild(tag);
            div.appendChild(card);
            return div;
        }
        el.appendChild(row('Most valuable', match.mostName, match.mostAmount));
        el.appendChild(row('Least valuable', match.leastName, match.leastAmount));
        el.hidden = false;
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
        fact('Price', priceLine(card));
        fact('Legal', (card.legalFormats && card.legalFormats.length) ? card.legalFormats.join(', ') : '');

        if (card.oracleText) {
            const oracle = document.createElement('p');
            oracle.className = 'cube-cardlookup-oracle';
            // Oracle text carries real newlines (and DFC face breaks); keep them.
            oracle.textContent = card.oracleText;
            facts.appendChild(oracle);
        }

        const link = document.createElement('a');
        link.className = 'btn btn-secondary cube-cardlookup-link';
        link.href = scryfallCardUrl(card.name);
        link.target = '_blank';
        link.rel = 'noopener';
        link.textContent = 'View on Scryfall ↗';
        facts.appendChild(link);

        // On-demand rulings: a button that fetches /magic/api/rulings for this
        // card and renders them into the box below (wired in wireCardLookup).
        const rulingsBtn = document.createElement('button');
        rulingsBtn.type = 'button';
        rulingsBtn.className = 'btn btn-secondary cube-rulings-btn';
        rulingsBtn.setAttribute('data-load-rulings', '');
        rulingsBtn.setAttribute('data-card-name', card.name);
        rulingsBtn.textContent = 'Show rulings';
        facts.appendChild(rulingsBtn);

        // On-demand combos: fetches /magic/api/combos for this card.
        const combosBtn = document.createElement('button');
        combosBtn.type = 'button';
        combosBtn.className = 'btn btn-secondary cube-combos-btn';
        combosBtn.setAttribute('data-load-combos', '');
        combosBtn.setAttribute('data-card-name', card.name);
        combosBtn.textContent = 'Show combos';
        facts.appendChild(combosBtn);

        wrap.appendChild(facts);
        container.appendChild(wrap);

        const rulingsBox = document.createElement('div');
        rulingsBox.className = 'cube-rulings';
        rulingsBox.setAttribute('data-rulings-result', '');
        rulingsBox.hidden = true;
        container.appendChild(rulingsBox);

        const combosBox = document.createElement('div');
        combosBox.className = 'cube-combos';
        combosBox.setAttribute('data-combos-result', '');
        combosBox.hidden = true;
        container.appendChild(combosBox);
        return container;
    }

    /** Renders a card's combos (pieces → produces, each linked), or a friendly empty state. */
    function renderCombos(container, data) {
        container.replaceChildren();
        const combos = (data && data.combos) || [];

        const head = document.createElement('div');
        head.className = 'cube-combos-head';
        const h = document.createElement('h4');
        h.className = 'cube-combos-h';
        h.textContent = 'Combos';
        head.appendChild(h);
        if (combos.length) {
            const count = document.createElement('span');
            count.className = 'cube-combos-count';
            count.textContent = String(combos.length);
            head.appendChild(count);
        }
        container.appendChild(head);

        if (!combos.length) {
            const p = document.createElement('p');
            p.className = 'cube-combos-empty';
            p.textContent = 'No combos found for this card on Commander Spellbook.';
            container.appendChild(p);
            return container;
        }

        // Renders a labelled row of card-name chips ("Needs …", "Makes …").
        function chipRow(rowClass, label, names, chipClass) {
            const row = document.createElement('div');
            row.className = rowClass;
            const tag = document.createElement('span');
            tag.className = 'cube-combo-label';
            tag.textContent = label;
            row.appendChild(tag);
            (names || []).forEach(function (name) {
                const chip = document.createElement('span');
                chip.className = chipClass;
                chip.textContent = name;
                row.appendChild(chip);
            });
            return row;
        }

        const ul = document.createElement('ul');
        ul.className = 'cube-combos-list';
        combos.forEach(function (combo, i) {
            const li = document.createElement('li');
            li.className = 'cube-combo';

            const num = document.createElement('span');
            num.className = 'cube-combo-num';
            num.setAttribute('aria-hidden', 'true');
            num.textContent = String(i + 1);
            li.appendChild(num);

            const body = document.createElement('div');
            body.className = 'cube-combo-body';
            body.appendChild(chipRow('cube-combo-uses', 'Needs', combo.uses, 'cube-combo-piece'));
            body.appendChild(chipRow('cube-combo-produces', 'Makes', combo.produces, 'cube-combo-result'));
            if (combo.url) {
                const a = document.createElement('a');
                a.className = 'cube-combo-link';
                a.href = combo.url;
                a.target = '_blank';
                a.rel = 'noopener';
                a.textContent = 'View combo ↗';
                body.appendChild(a);
            }
            li.appendChild(body);
            ul.appendChild(li);
        });
        container.appendChild(ul);
        return container;
    }

    /** Renders a card's official rulings (date + note each), or a friendly empty state. */
    function renderRulings(container, data) {
        container.replaceChildren();
        const h = document.createElement('h4');
        h.className = 'cube-rulings-h';
        h.textContent = 'Rulings';
        container.appendChild(h);
        const rulings = (data && data.rulings) || [];
        if (!rulings.length) {
            const p = document.createElement('p');
            p.className = 'cube-rulings-empty';
            p.textContent = 'No official rulings have been published for this card.';
            container.appendChild(p);
            return container;
        }
        const ul = document.createElement('ul');
        ul.className = 'cube-rulings-list';
        rulings.forEach(function (r) {
            const li = document.createElement('li');
            li.className = 'cube-ruling';
            if (r.publishedAt) {
                const date = document.createElement('span');
                date.className = 'cube-ruling-date';
                date.textContent = r.publishedAt;
                li.appendChild(date);
            }
            const text = document.createElement('span');
            text.className = 'cube-ruling-text';
            text.textContent = r.comment;
            li.appendChild(text);
            ul.appendChild(li);
        });
        container.appendChild(ul);
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

    /**
     * Renders a deck-legality verdict: a legal/illegal headline, then the
     * offending cards bucketed (banned / not in format / restricted / unknown).
     */
    function renderLegality(container, data) {
        container.replaceChildren();
        const headline = document.createElement('p');
        headline.className = 'cube-legality-headline ' + (data.legal ? 'is-legal' : 'is-illegal');
        headline.textContent = (data.legal ? '✅ Legal in ' : '🚫 Not legal in ') + data.format +
            ' — checked ' + data.total + ' card' + (data.total === 1 ? '' : 's');
        container.appendChild(headline);

        function bucket(title, names, kind) {
            if (!names || !names.length) return;
            const block = document.createElement('div');
            block.className = 'cube-legality-group cube-legality-' + kind;
            const h = document.createElement('h4');
            h.className = 'cube-legality-h';
            h.textContent = title + ' (' + names.length + ')';
            block.appendChild(h);
            const ul = document.createElement('ul');
            ul.className = 'cube-legality-list';
            names.forEach(function (name) {
                const li = document.createElement('li');
                li.textContent = name;
                ul.appendChild(li);
            });
            block.appendChild(ul);
            container.appendChild(block);
        }
        bucket('Banned', data.banned, 'banned');
        bucket('Not in format', data.notLegal, 'notlegal');
        bucket('Restricted (max 1)', data.restricted, 'restricted');
        bucket('Unknown', data.unknown, 'unknown');
        if (data.legal && (!data.restricted || !data.restricted.length)) {
            const ok = document.createElement('p');
            ok.className = 'cube-legality-empty';
            ok.textContent = 'Every card is legal in ' + data.format + '.';
            container.appendChild(ok);
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

    /** A flat grid of search-result cards, each a thumbnail tile (like the preview). */
    function renderSearchResults(container, cards) {
        container.replaceChildren();
        cards.forEach(function (card) {
            container.appendChild(cardTile(card));
        });
        return container;
    }

    /** The per-pack "copy this seat's link" button in a pack heading. */
    function packLinkButton(url, index) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'cube-pack-link';
        btn.textContent = '🔗';
        btn.title = 'Copy a link to pack ' + index;
        btn.setAttribute('aria-label', 'Copy a link to pack ' + index);
        btn.setAttribute('data-pack-link', url);
        btn.addEventListener('click', function (e) {
            // The heading is a <summary>; copying shouldn't collapse the pack.
            e.preventDefault();
            e.stopPropagation();
            copyToClipboard(url);
            const previous = btn.textContent;
            btn.textContent = '✓';
            if (root && root.setTimeout) root.setTimeout(function () { btn.textContent = previous; }, 1500);
        });
        return btn;
    }

    /**
     * Renders the dealt packs. When [options.packLink] is given it's called
     * with a 1-based pack number and, if it returns a URL, that pack gets a
     * button copying its own link — which is how a drafter is handed their
     * seat instead of the whole deal.
     */
    function renderPacks(container, packs, options) {
        const packLink = (options && options.packLink) || function () { return null; };
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
            count.textContent = cardCountLabel(pack.length);
            heading.appendChild(count);
            summary.appendChild(heading);
            const url = packLink(i + 1);
            if (url) summary.appendChild(packLinkButton(url, i + 1));
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

    function show(el) { if (el) el.hidden = false; }
    function hide(el) { if (el) el.hidden = true; }

    /** Best-effort clipboard write; silent when the browser blocks it. */
    function copyToClipboard(text) {
        if (root && root.navigator && root.navigator.clipboard) {
            root.navigator.clipboard.writeText(text).then(function () {}, function () {});
        }
    }

    /** Renders the "couldn't find these names" warning after a list lookup. */
    function renderNotFound(el, names) {
        if (!el) return;
        if (!names || !names.length) {
            el.hidden = true;
            el.textContent = '';
            return;
        }
        el.textContent = notFoundText(names);
        el.hidden = false;
    }

    /**
     * Presents the card-detail panel as a dismissible modal overlay. Creates
     * one shared backdrop + close button in the document and toggles the
     * panel's `is-modal` class to lift it into a centered, scrollable sheet
     * over the backdrop. Dismissed by the close button, a backdrop click, or
     * Escape, each running `onClose` (to clear the panel and restore focus).
     * Returns { open, close, isOpen }, or null when there's nowhere to mount.
     */
    function cardDetailModal(doc, panel, onClose) {
        if (!doc.body || !panel) return null;
        const backdrop = doc.createElement('div');
        backdrop.className = 'cube-detail-backdrop';
        backdrop.hidden = true;
        const closeBtn = doc.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'cube-detail-close';
        closeBtn.setAttribute('aria-label', 'Close');
        closeBtn.textContent = '✕';
        backdrop.appendChild(closeBtn);
        doc.body.appendChild(backdrop);

        let isOpen = false;
        function open() {
            isOpen = true;
            panel.classList.add('is-modal');
            backdrop.hidden = false;
            if (closeBtn.focus) closeBtn.focus();
        }
        function close() {
            if (!isOpen) return;
            isOpen = false;
            panel.classList.remove('is-modal');
            backdrop.hidden = true;
            if (typeof onClose === 'function') onClose();
        }
        // A click on the dark backdrop dismisses; the lifted panel is a
        // separate subtree, so clicks inside it never reach here.
        backdrop.addEventListener('click', function (e) { if (e.target === backdrop) close(); });
        closeBtn.addEventListener('click', close);
        doc.addEventListener('keydown', function (e) { if (e.key === 'Escape' && isOpen) close(); });
        return { open: open, close: close, isOpen: function () { return isOpen; } };
    }

    /** Renders a set's headline facts (type, release date, card count) with a Scryfall link. */
    function renderSet(container, set) {
        container.replaceChildren();
        const h = document.createElement('h3');
        h.className = 'cube-setinfo-h';
        h.textContent = set.name + ' (' + set.code + ')';
        container.appendChild(h);
        function fact(label, value) {
            if (value == null || value === '') return;
            const p = document.createElement('p');
            p.className = 'cube-setinfo-fact';
            const b = document.createElement('strong');
            b.textContent = label + ' ';
            p.appendChild(b);
            p.appendChild(document.createTextNode(String(value)));
            container.appendChild(p);
        }
        fact('Type', set.setType);
        fact('Released', set.releasedAt);
        fact('Cards', set.cardCount);
        if (set.scryfallUri) {
            const a = document.createElement('a');
            a.className = 'btn btn-secondary';
            a.href = set.scryfallUri;
            a.target = '_blank';
            a.rel = 'noopener';
            a.textContent = 'View on Scryfall ↗';
            container.appendChild(a);
        }
        return container;
    }

    /** Renders a keyword glossary entry. */
    function renderRule(container, rule) {
        container.replaceChildren();
        const h = document.createElement('h3');
        h.className = 'cube-rule-h';
        h.textContent = rule.keyword;
        const p = document.createElement('p');
        p.className = 'cube-rule-text';
        p.textContent = rule.text;
        container.appendChild(h);
        container.appendChild(p);
        return container;
    }

    /** Renders the user's price watches with a Remove button each; [onRemove] gets the id. */
    function renderWatches(container, watches, onRemove) {
        container.replaceChildren();
        if (!watches || !watches.length) {
            const p = document.createElement('p');
            p.className = 'cube-watches-empty muted';
            p.textContent = 'No price watches yet. Add one above.';
            container.appendChild(p);
            return container;
        }
        const ul = document.createElement('ul');
        ul.className = 'cube-watches-list';
        watches.forEach(function (w) {
            const li = document.createElement('li');
            li.className = 'cube-watch';
            const span = document.createElement('span');
            span.className = 'cube-watch-text';
            span.textContent = watchLine(w);
            li.appendChild(span);
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'btn btn-secondary cube-watch-remove';
            btn.textContent = 'Remove';
            btn.addEventListener('click', function () { onRemove(w.id); });
            li.appendChild(btn);
            ul.appendChild(li);
        });
        container.appendChild(ul);
        return container;
    }


    const api = {
        applyPackShape: applyPackShape,
        cardDetailModal: cardDetailModal,
        copyToClipboard: copyToClipboard,
        downloadText: downloadText,
        hide: hide,
        prefersTap: prefersTap,
        readFileText: readFileText,
        renderAnalytics: renderAnalytics,
        renderCardLookup: renderCardLookup,
        renderColorPairs: renderColorPairs,
        renderColorPips: renderColorPips,
        renderCombos: renderCombos,
        renderDiff: renderDiff,
        renderDistribution: renderDistribution,
        renderDuplicates: renderDuplicates,
        renderGroups: renderGroups,
        renderLegality: renderLegality,
        renderManaCurve: renderManaCurve,
        renderNotFound: renderNotFound,
        renderPacks: renderPacks,
        renderRarity: renderRarity,
        renderRule: renderRule,
        renderRulings: renderRulings,
        renderSearchResults: renderSearchResults,
        renderSet: renderSet,
        renderTotalValue: renderTotalValue,
        renderTypeBreakdown: renderTypeBreakdown,
        renderValueExtremes: renderValueExtremes,
        renderWatches: renderWatches,
        setStatus: setStatus,
        show: show,
    };
    if (root) root.TobyCubeRender = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
