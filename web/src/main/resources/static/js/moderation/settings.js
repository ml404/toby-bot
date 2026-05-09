// Settings tab: client-side config search filter. The generic
// .config-row save handler lives in moderationCommon.js so it works on
// every page that has config rows; this file only adds the live filter.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;

    const input = document.getElementById('config-search');
    const count = document.getElementById('config-search-count');
    if (!input) return;

    // Build a per-row haystack of (label text + data-key) lowercased so a
    // user can type "rtp" (free-text) or "JACKPOT_RTP" (the enum-style
    // key) and either matches. Cache once at init — config-row count is
    // O(50) so per-keystroke DOM-walk would be wasteful.
    const rows = Array.from(document.querySelectorAll('.config-row[data-key]'));
    const haystackByRow = new Map(
        rows.map(row => {
            const label = row.querySelector('label')?.textContent || '';
            const key = row.dataset.key || '';
            return [row, (label + ' ' + key).toLowerCase()];
        })
    );
    const total = rows.length;

    const sections = Array.from(document.querySelectorAll('details.config-section'));
    // Snapshot which sections were open before any search ran so we can
    // restore that state when the query is cleared. Force-open while
    // searching so matches deep inside collapsed sections are visible.
    let openSnapshot = null;

    function snapshotIfNeeded() {
        if (openSnapshot !== null) return;
        openSnapshot = sections.map(s => s.open);
    }

    function restoreSnapshot() {
        if (openSnapshot === null) return;
        sections.forEach((s, i) => { s.open = openSnapshot[i]; });
        openSnapshot = null;
    }

    function apply(query) {
        const q = query.trim().toLowerCase();
        let shown = 0;
        rows.forEach(row => {
            const hit = q === '' || (haystackByRow.get(row) || '').includes(q);
            row.classList.toggle('is-hidden', !hit);
            if (hit) shown++;
        });
        // Hide sections whose rows are all filtered out; force-open the
        // rest so users can see the matches without opening each
        // <details> by hand.
        sections.forEach(section => {
            const sectionRows = Array.from(section.querySelectorAll('.config-row'));
            const anyVisible = sectionRows.some(r => !r.classList.contains('is-hidden'));
            section.classList.toggle('is-hidden', !anyVisible && q !== '');
            if (q !== '' && anyVisible) section.open = true;
        });
        if (count) {
            count.textContent = q === '' ? '' : shown + ' of ' + total;
        }
        // URL hash sync — write back via replaceState so the back button
        // doesn't replay every keystroke.
        const hash = q === '' ? '' : '#search=' + encodeURIComponent(q);
        if (window.location.hash !== hash) {
            try {
                history.replaceState(null, '', window.location.pathname + window.location.search + hash);
            } catch (e) { /* ignore */ }
        }
    }

    input.addEventListener('input', () => {
        if (input.value.trim() !== '') snapshotIfNeeded();
        else restoreSnapshot();
        apply(input.value);
    });
    input.addEventListener('keydown', e => {
        if (e.key === 'Escape') {
            input.value = '';
            restoreSnapshot();
            apply('');
        }
    });

    // Restore from URL hash on page load (e.g. someone shares
    // /moderation/123/settings#search=jackpot).
    const initial = (window.location.hash.match(/^#search=(.*)$/) || [])[1];
    if (initial) {
        const decoded = decodeURIComponent(initial);
        input.value = decoded;
        snapshotIfNeeded();
        apply(decoded);
    }
})();
