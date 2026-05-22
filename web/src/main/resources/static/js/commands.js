/* commands.js — search, category filter, copy-to-clipboard, and URL hash
   sync for the commands page. No framework. The CSS handles the category
   show/hide via [data-active-cat]; this script only filters cards by
   search text and toggles per-section visibility when a category empties. */
(function () {
    'use strict';

    const page = document.querySelector('.commands-page');
    if (!page) return;

    const searchInput = document.getElementById('commands-search');
    const chips = Array.from(document.querySelectorAll('.cat-chip'));
    const sections = Array.from(document.querySelectorAll('.cat-section'));
    const cards = Array.from(document.querySelectorAll('.cmd-card'));
    const emptyState = document.querySelector('[data-empty]');
    const clearButton = document.querySelector('[data-clear]');

    let activeCat = 'all';
    let query = '';
    let rafId = 0;

    function readHash() {
        const hash = (location.hash || '').replace(/^#/, '');
        if (!hash) return;
        const params = new URLSearchParams(hash);
        const cat = params.get('cat');
        const q = params.get('q');
        if (cat) activeCat = cat;
        if (q) query = q;
    }

    function writeHash() {
        const params = new URLSearchParams();
        if (activeCat && activeCat !== 'all') params.set('cat', activeCat);
        if (query) params.set('q', query);
        const next = params.toString();
        const url = next ? '#' + next : location.pathname + location.search;
        history.replaceState(null, '', url);
    }

    function applyChipState() {
        chips.forEach((chip) => {
            const isActive = chip.dataset.cat === activeCat;
            chip.classList.toggle('is-active', isActive);
            chip.setAttribute('aria-pressed', isActive ? 'true' : 'false');
        });
    }

    function applyFilters() {
        page.dataset.activeCat = activeCat;
        const needle = query.trim().toLowerCase();
        let visibleTotal = 0;

        sections.forEach((section) => {
            const sectionCat = section.dataset.cat;
            const catMatches = activeCat === 'all' || sectionCat === activeCat;
            let visibleInSection = 0;

            section.querySelectorAll('.cmd-card').forEach((card) => {
                const haystack = card.dataset.search || '';
                const matches = !needle || haystack.indexOf(needle) !== -1;
                const show = catMatches && matches;
                card.hidden = !show;
                if (show) visibleInSection += 1;
            });

            // Even though CSS already handles category-only hiding, we hide
            // sections that are empty *because of a search query* (so an
            // "All" view with a needle doesn't render empty category headers).
            section.hidden = catMatches && visibleInSection === 0 && needle.length > 0;
            visibleTotal += visibleInSection;
        });

        if (emptyState) emptyState.hidden = visibleTotal !== 0;
    }

    function scheduleApply() {
        if (rafId) cancelAnimationFrame(rafId);
        rafId = requestAnimationFrame(() => {
            rafId = 0;
            applyFilters();
            writeHash();
        });
    }

    function setCategory(cat) {
        activeCat = cat || 'all';
        applyChipState();
        scheduleApply();
    }

    function clearAll() {
        activeCat = 'all';
        query = '';
        if (searchInput) searchInput.value = '';
        applyChipState();
        scheduleApply();
        if (searchInput) searchInput.focus();
    }

    // ---- Wire up listeners ----
    if (searchInput) {
        searchInput.addEventListener('input', () => {
            query = searchInput.value;
            scheduleApply();
        });
        searchInput.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && searchInput.value !== '') {
                e.preventDefault();
                searchInput.value = '';
                query = '';
                scheduleApply();
            }
        });
    }

    chips.forEach((chip) => {
        chip.addEventListener('click', () => setCategory(chip.dataset.cat));
    });

    if (clearButton) clearButton.addEventListener('click', clearAll);

    // Copy buttons — copies the slash-command name to the clipboard and
    // flashes a toast. Uses the shared window.toast helper from toasts.js.
    document.querySelectorAll('[data-copy]').forEach((btn) => {
        btn.addEventListener('click', () => {
            const card = btn.closest('.cmd-card');
            if (!card) return;
            const text = '/' + (card.dataset.name || '');
            const flash = () => {
                btn.classList.add('is-copied');
                setTimeout(() => btn.classList.remove('is-copied'), 1200);
                if (window.toast) window.toast('Copied ' + text, 'success');
            };
            const fail = () => {
                if (window.toast) window.toast('Copy failed', 'error');
            };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(flash).catch(fail);
            } else {
                // Fallback for older browsers / insecure contexts.
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.setAttribute('readonly', '');
                ta.style.position = 'absolute';
                ta.style.left = '-9999px';
                document.body.appendChild(ta);
                ta.select();
                try { document.execCommand('copy'); flash(); }
                catch (_) { fail(); }
                finally { document.body.removeChild(ta); }
            }
        });
    });

    // Global "/" shortcut to focus the search — matches GitHub / Discord.
    document.addEventListener('keydown', (e) => {
        if (e.key !== '/') return;
        const t = e.target;
        if (!t) return;
        const tag = t.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || t.isContentEditable) return;
        e.preventDefault();
        if (searchInput) {
            searchInput.focus();
            searchInput.select();
        }
    });

    // ---- Boot ----
    readHash();
    if (query && searchInput) searchInput.value = query;
    applyChipState();
    applyFilters();
})();
