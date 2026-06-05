// MTG cube workshop page. Three independent tools (as-fan calculator,
// cube preview, pack generator) each POST-free GET against the /cube/api/*
// JSON endpoints and render the result. Pure helpers (URL builders,
// formatting, DOM renderers) are exported for Jest; the DOM wiring only
// runs in a browser where the forms exist.
(function (root) {
    'use strict';

    function formatAsFan(value) {
        return Number(value).toFixed(2);
    }

    function asfanUrl(p) {
        const q = new URLSearchParams({
            total: p.total,
            cubeSize: p.cubeSize,
            packSize: p.packSize,
        });
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

    function renderDistribution(tbody, distribution) {
        tbody.replaceChildren();
        distribution.forEach(function (row) {
            const tr = document.createElement('tr');
            [row.category, String(row.count), formatAsFan(row.asFan) + ' / pack'].forEach(function (text) {
                const td = document.createElement('td');
                td.textContent = text;
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });
        return tbody;
    }

    function renderPacks(container, packs) {
        container.replaceChildren();
        packs.forEach(function (pack, i) {
            const card = document.createElement('div');
            card.className = 'cube-pack';
            const heading = document.createElement('h3');
            heading.textContent = 'Pack ' + (i + 1) + ' (' + pack.length + ')';
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

    function wireAsFan(doc) {
        const form = doc.querySelector('[data-form="asfan"]');
        if (!form) return;
        const status = statusFor(doc, 'asfan');
        const result = doc.querySelector('[data-result="asfan"]');
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
            fetch(asfanUrl(params))
                .then(function (r) { return r.json(); })
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Invalid inputs.'); return; }
                    setStatus(status, '');
                    result.textContent = formatAsFan(json.value) + ' of that type per pack.';
                    result.hidden = false;
                })
                .catch(function () { setStatus(status, 'Something went wrong. Try again.'); });
        });
    }

    function wirePreview(doc) {
        const form = doc.querySelector('[data-form="preview"]');
        if (!form) return;
        const status = statusFor(doc, 'preview');
        const table = doc.querySelector('[data-result="preview"]');
        const tbody = table ? table.querySelector('tbody') : null;
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const data = new FormData(form);
            const params = { query: (data.get('query') || '').toString().trim(), packSize: data.get('packSize') };
            if (!params.query) return;
            setStatus(status, 'Fetching from Scryfall…');
            table.hidden = true;
            fetch(previewUrl(params))
                .then(function (r) { return r.json(); })
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'No results.'); return; }
                    setStatus(status, params.query + ' → ' + json.poolSize + ' cards');
                    renderDistribution(tbody, json.distribution);
                    table.hidden = false;
                })
                .catch(function () { setStatus(status, 'Something went wrong reaching Scryfall.'); });
        });
    }

    function wireGenerate(doc) {
        const form = doc.querySelector('[data-form="generate"]');
        if (!form) return;
        const status = statusFor(doc, 'generate');
        const summary = doc.querySelector('[data-summary="generate"]');
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
            result.hidden = true;
            fetch(generateUrl(params))
                .then(function (r) { return r.json(); })
                .then(function (json) {
                    if (!json.ok) { setStatus(status, json.error || 'Could not build packs.'); return; }
                    setStatus(status, '');
                    summary.textContent = 'Dealt ' + json.packCount + ' packs of ' + json.packSize +
                        ' from a ' + json.poolSize + '-card pool.';
                    summary.hidden = false;
                    renderPacks(result, json.packs);
                    result.hidden = false;
                })
                .catch(function () { setStatus(status, 'Something went wrong building packs.'); });
        });
    }

    function wire(doc) {
        wireAsFan(doc);
        wirePreview(doc);
        wireGenerate(doc);
    }

    if (root && root.document) wire(root.document);

    const api = {
        formatAsFan: formatAsFan,
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
