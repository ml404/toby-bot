(function () {
    'use strict';

    const API_BASE = 'https://www.dnd5eapi.co/api';
    const form = document.querySelector('[data-form="lookup"]');
    if (!form) return;
    const statusEl = form.parentElement.querySelector('[data-status]');
    const suggestionsEl = form.parentElement.querySelector('[data-suggestions]');
    const resultEl = form.parentElement.querySelector('[data-result]');

    const TYPE_LABELS = {
        'spells': 'spell',
        'conditions': 'condition',
        'rule-sections': 'rule',
        'features': 'feature'
    };

    function slug(s) {
        return s.trim().toLowerCase().replace(/\s+/g, '-');
    }

    function clear() {
        statusEl.textContent = '';
        suggestionsEl.hidden = true;
        suggestionsEl.replaceChildren();
        resultEl.hidden = true;
        resultEl.replaceChildren();
    }

    function setStatus(msg) {
        statusEl.textContent = msg;
    }

    function renderResult(type, data) {
        resultEl.replaceChildren();
        const title = document.createElement('h2');
        title.textContent = data.name || '(unnamed)';
        resultEl.appendChild(title);

        const meta = document.createElement('p');
        meta.className = 'muted';
        const parts = [];
        if (type === 'spells') {
            if (data.level !== undefined) parts.push(`Level ${data.level}`);
            if (data.school?.name) parts.push(data.school.name);
            if (data.casting_time) parts.push(`Cast: ${data.casting_time}`);
            if (data.range) parts.push(`Range: ${data.range}`);
            if (data.duration) parts.push(`Duration: ${data.duration}`);
        }
        if (parts.length) {
            meta.textContent = parts.join(' • ');
            resultEl.appendChild(meta);
        }

        const desc = Array.isArray(data.desc) ? data.desc
            : Array.isArray(data.description) ? data.description
            : data.desc ? [data.desc]
            : [];
        desc.forEach(p => {
            const para = document.createElement('p');
            para.textContent = p;
            resultEl.appendChild(para);
        });

        if (Array.isArray(data.higher_level) && data.higher_level.length) {
            const h = document.createElement('h3');
            h.textContent = 'At higher levels';
            resultEl.appendChild(h);
            data.higher_level.forEach(p => {
                const para = document.createElement('p');
                para.textContent = p;
                resultEl.appendChild(para);
            });
        }

        resultEl.hidden = false;
    }

    async function fetchExact(type, query) {
        const r = await fetch(`${API_BASE}/${type}/${encodeURIComponent(slug(query))}`);
        if (!r.ok) return null;
        const data = await r.json();
        // dnd5eapi returns {error: ...} on not-found with 404, but also can
        // return an object with no "name". Treat missing name as miss.
        if (!data || !data.name) return null;
        return data;
    }

    async function fetchSuggestions(type, query) {
        const r = await fetch(`${API_BASE}/${type}?name=${encodeURIComponent(query)}`);
        if (!r.ok) return [];
        const data = await r.json();
        return Array.isArray(data.results) ? data.results : [];
    }

    function showSuggestions(type, suggestions) {
        suggestionsEl.replaceChildren();
        const label = TYPE_LABELS[type] || type;
        const heading = document.createElement('li');
        heading.className = 'lookup-suggestions-heading muted';
        heading.textContent = `No exact match. Did you mean:`;
        suggestionsEl.appendChild(heading);

        suggestions.forEach(s => {
            const li = document.createElement('li');
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'lookup-suggestion';
            btn.textContent = s.name;
            btn.addEventListener('click', async () => {
                clear();
                setStatus(`Loading ${label} "${s.name}"…`);
                const exact = await fetchExact(type, s.index || s.name);
                if (exact) {
                    setStatus('');
                    renderResult(type, exact);
                } else {
                    setStatus(`Couldn't load "${s.name}".`);
                }
            });
            li.appendChild(btn);
            suggestionsEl.appendChild(li);
        });
        suggestionsEl.hidden = false;
    }

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clear();
        const data = new FormData(form);
        const type = data.get('type');
        const query = (data.get('query') || '').toString().trim();
        if (!query) return;

        const label = TYPE_LABELS[type] || type;
        setStatus(`Searching for ${label} "${query}"…`);

        try {
            const exact = await fetchExact(type, query);
            if (exact) {
                setStatus('');
                renderResult(type, exact);
                return;
            }
            const suggestions = await fetchSuggestions(type, query);
            if (suggestions.length) {
                setStatus('');
                showSuggestions(type, suggestions);
            } else {
                setStatus(`Sorry, nothing was returned for ${label} "${query}".`);
            }
        } catch (err) {
            setStatus('Something went wrong reaching the D&D API. Please try again.');
        }
    });
})();
