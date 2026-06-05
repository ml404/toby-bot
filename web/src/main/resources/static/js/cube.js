// MTG cube workshop page. A guided, tabbed tool (generate packs / preview
// a cube / as-fan calculator) over the /cube/api/* JSON endpoints. The
// emphasis is on showing the actual CARDS: generated packs and the preview
// both list real card names linked to Scryfall. Pure helpers (URL builders,
// formatting, colours, card links, hash↔tab mapping, DOM renderers) are
// exported for Jest; the DOM wiring only runs in a browser.
(function (root) {
    'use strict';

    const TABS = ['generate', 'preview', 'asfan'];

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
                pack.map(function (n) { return '  ' + n; }).join('\n');
        }).join('\n\n') + '\n';
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

    /** A card name as a Scryfall link (opens in a new tab). */
    function cardLink(name) {
        const a = document.createElement('a');
        a.className = 'cube-card-link';
        a.href = scryfallCardUrl(name);
        a.target = '_blank';
        a.rel = 'noopener';
        a.textContent = name;
        return a;
    }

    function asFanBar(category, asFan, max) {
        const color = categoryColor(category);
        const pct = max > 0 ? Math.max(2, Math.round((asFan / max) * 100)) : 0;
        const track = document.createElement('div');
        track.className = 'cube-bar-track';
        const fill = document.createElement('div');
        fill.className = 'cube-bar-fill';
        fill.style.width = pct + '%';
        fill.style.background = color;
        track.appendChild(fill);
        return track;
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

    /** The preview: each colour/land group with its as-fan AND its cards. */
    function renderGroups(container, groups) {
        container.replaceChildren();
        const max = groups.reduce(function (m, g) { return Math.max(m, g.asFan); }, 0);
        groups.forEach(function (group) {
            const block = document.createElement('section');
            block.className = 'cube-group';

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
            block.appendChild(head);

            const list = document.createElement('ul');
            list.className = 'cube-card-list';
            group.cards.forEach(function (name) {
                const li = document.createElement('li');
                li.appendChild(cardLink(name));
                list.appendChild(li);
            });
            block.appendChild(list);
            container.appendChild(block);
        });
        return container;
    }

    function renderPacks(container, packs) {
        container.replaceChildren();
        packs.forEach(function (pack, i) {
            const card = document.createElement('div');
            card.className = 'cube-pack';
            const heading = document.createElement('h3');
            heading.textContent = 'Pack ' + (i + 1);
            const count = document.createElement('span');
            count.className = 'cube-pack-count';
            count.textContent = pack.length + ' cards';
            heading.appendChild(count);
            card.appendChild(heading);
            const list = document.createElement('ul');
            list.className = 'cube-card-list';
            pack.forEach(function (name) {
                const li = document.createElement('li');
                li.appendChild(cardLink(name));
                list.appendChild(li);
            });
            card.appendChild(list);
            container.appendChild(card);
        });
        return container;
    }

    function setStatus(el, msg) {
        if (el) el.textContent = msg;
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

    /** Disables the form's submit button while a request is in flight. */
    function withBusy(form, busy) {
        const btn = form.querySelector('button[type="submit"]');
        if (btn) btn.disabled = busy;
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

    function wirePreview(doc) {
        const form = q(doc, '[data-form="preview"]');
        if (!form) return;
        const status = statusFor(doc, 'preview');
        const empty = emptyFor(doc, 'preview');
        const groups = q(doc, '[data-result="preview"]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const params = { query: (data.get('query') || '').toString().trim(), packSize: data.get('packSize') };
            if (!params.query) return;
            setStatus(status, 'Fetching cards from Scryfall…');
            hide(groups);
            withBusy(form, true);
            getJson(previewUrl(params))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'No results.'); return; }
                    setStatus(status, params.query + ' → ' + json.poolSize + ' cards');
                    hide(empty);
                    renderGroups(groups, json.groups);
                    show(groups);
                })
                .catch(function () { setStatus(status, 'Something went wrong reaching Scryfall.'); })
                .then(function () { withBusy(form, false); });
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
            const params = {
                query: (data.get('query') || '').toString().trim(),
                packs: data.get('packs'),
                packSize: data.get('packSize'),
                balanced: form.querySelector('[name="balanced"]').checked,
            };
            if (!params.query) return;
            setStatus(status, 'Drawing cards and dealing packs…');
            hide(summary); hide(actions); hide(breakdown); hide(result);
            withBusy(form, true);
            getJson(generateUrl(params))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not build packs.'); return; }
                    setStatus(status, '');
                    hide(empty);
                    lastPacks = json.packs;
                    summary.textContent = 'Dealt ' + json.packCount + ' packs of ' + json.packSize +
                        ' from a ' + json.poolSize + '-card pool. Click any card to view it on Scryfall.';
                    show(summary);
                    renderPacks(result, json.packs);
                    show(result);
                    show(actions);
                    renderDistribution(dist, json.distribution);
                    show(breakdown);
                })
                .catch(function () { setStatus(status, 'Something went wrong building packs.'); })
                .then(function () { withBusy(form, false); });
        });
    }

    function wire(doc) {
        wireTabs(doc);
        wireExamples(doc);
        wireAsFan(doc);
        wirePreview(doc);
        wireGenerate(doc);
    }

    if (root && root.document) wire(root.document);

    const api = {
        formatAsFan: formatAsFan,
        asfanSentence: asfanSentence,
        categoryColor: categoryColor,
        scryfallCardUrl: scryfallCardUrl,
        tabIdFromHash: tabIdFromHash,
        packsToText: packsToText,
        asfanUrl: asfanUrl,
        previewUrl: previewUrl,
        generateUrl: generateUrl,
        renderDistribution: renderDistribution,
        renderGroups: renderGroups,
        renderPacks: renderPacks,
    };
    if (root) root.TobyCube = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
