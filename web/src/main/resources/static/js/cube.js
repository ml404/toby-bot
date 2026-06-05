// MTG cube workshop page. A tabbed tool (as-fan calculator / cube preview
// / pack generator) over the /cube/api/* JSON endpoints. Pure helpers
// (URL builders, formatting, category colours, hash↔tab mapping, DOM
// renderers) are exported for Jest; the DOM wiring only runs in a browser
// where the tabs and forms exist.
(function (root) {
    'use strict';

    const TABS = ['asfan', 'preview', 'generate'];

    // Colour-pie palette for the as-fan bars. Tuned to read on the dark
    // dashboard background (pure black/white would vanish), but still
    // recognisably W/U/B/R/G + gold multicolour, silver colourless, brown land.
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

    /** The tab id encoded in a URL hash, or null if it isn't a real tab. */
    function tabIdFromHash(hash) {
        const id = (hash || '').replace(/^#/, '');
        return TABS.indexOf(id) >= 0 ? id : null;
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

    /** Renders the as-fan distribution as colour-coded, length-scaled bars. */
    function renderDistribution(container, distribution) {
        container.replaceChildren();
        const max = distribution.reduce(function (m, row) { return Math.max(m, row.asFan); }, 0);
        distribution.forEach(function (row) {
            const color = categoryColor(row.category);
            const pct = max > 0 ? Math.max(2, Math.round((row.asFan / max) * 100)) : 0;

            const rowEl = document.createElement('div');
            rowEl.className = 'cube-bar-row';

            const label = document.createElement('span');
            label.className = 'cube-bar-label';
            const swatch = document.createElement('span');
            swatch.className = 'cube-swatch';
            swatch.style.background = color;
            label.appendChild(swatch);
            label.appendChild(document.createTextNode(row.category));

            const track = document.createElement('div');
            track.className = 'cube-bar-track';
            const fill = document.createElement('div');
            fill.className = 'cube-bar-fill';
            fill.style.width = pct + '%';
            fill.style.background = color;
            track.appendChild(fill);

            const value = document.createElement('span');
            value.className = 'cube-bar-value';
            value.textContent = formatAsFan(row.asFan) + ' / pack · ' + row.count;

            rowEl.appendChild(label);
            rowEl.appendChild(track);
            rowEl.appendChild(value);
            container.appendChild(rowEl);
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
            pack.forEach(function (name) {
                const li = document.createElement('li');
                li.textContent = name;
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

    // --- DOM wiring (browser only) -------------------------------------

    function statusFor(doc, name) {
        return doc.querySelector('[data-status="' + name + '"]');
    }

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
                activateTab(doc, 'preview');
            });
        });
    }

    function getJson(url) {
        return fetch(url).then(function (r) { return r.json(); });
    }

    function wireAsFan(doc) {
        const form = doc.querySelector('[data-form="asfan"]');
        if (!form) return;
        const status = statusFor(doc, 'asfan');
        const result = doc.querySelector('[data-result="asfan"]');
        const number = doc.querySelector('[data-asfan-number]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const params = {
                total: data.get('total'),
                cubeSize: data.get('cubeSize'),
                packSize: data.get('packSize'),
            };
            setStatus(status, 'Calculating…');
            result.hidden = true;
            getJson(asfanUrl(params))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Invalid inputs.'); return; }
                    setStatus(status, '');
                    number.textContent = formatAsFan(json.value);
                    result.hidden = false;
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); });
        });
    }

    function wirePreview(doc) {
        const form = doc.querySelector('[data-form="preview"]');
        if (!form) return;
        const status = statusFor(doc, 'preview');
        const dist = doc.querySelector('[data-result="preview"]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const params = { query: (data.get('query') || '').toString().trim(), packSize: data.get('packSize') };
            if (!params.query) return;
            setStatus(status, 'Fetching from Scryfall…');
            dist.hidden = true;
            getJson(previewUrl(params))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'No results.'); return; }
                    setStatus(status, params.query + ' → ' + json.poolSize + ' cards');
                    renderDistribution(dist, json.distribution);
                    dist.hidden = false;
                })
                .catch(function () { setStatus(status, 'Something went wrong reaching Scryfall.'); });
        });
    }

    function wireGenerate(doc) {
        const form = doc.querySelector('[data-form="generate"]');
        if (!form) return;
        const status = statusFor(doc, 'generate');
        const summary = doc.querySelector('[data-summary="generate"]');
        const dist = doc.querySelector('[data-dist="generate"]');
        const result = doc.querySelector('[data-result="generate"]');
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
            setStatus(status, 'Building packs…');
            summary.hidden = true;
            dist.hidden = true;
            result.hidden = true;
            getJson(generateUrl(params))
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not build packs.'); return; }
                    setStatus(status, '');
                    summary.textContent = 'Dealt ' + json.packCount + ' packs of ' + json.packSize +
                        ' from a ' + json.poolSize + '-card pool.';
                    summary.hidden = false;
                    renderDistribution(dist, json.distribution);
                    dist.hidden = false;
                    renderPacks(result, json.packs);
                    result.hidden = false;
                })
                .catch(function () { setStatus(status, 'Something went wrong building packs.'); });
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
        categoryColor: categoryColor,
        tabIdFromHash: tabIdFromHash,
        asfanUrl: asfanUrl,
        previewUrl: previewUrl,
        generateUrl: generateUrl,
        renderDistribution: renderDistribution,
        renderPacks: renderPacks,
    };
    if (root) root.TobyCube = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
