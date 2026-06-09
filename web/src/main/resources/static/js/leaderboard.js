// Show only the standings rows whose member name contains `query`
// (case-insensitive, trimmed). Returns the number of visible rows.
// Pure DOM helper, exported for unit tests.
function filterStandingsRows(rows, query) {
    const q = (query || '').trim().toLowerCase();
    let visible = 0;
    rows.forEach(function (tr) {
        const name = tr.getAttribute('data-name') || '';
        const match = q === '' || name.indexOf(q) !== -1;
        tr.hidden = !match;
        if (match) visible += 1;
    });
    return visible;
}

(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;

    // Persist open/closed state per section per guild, so a user who prefers
    // the TobyCoin table collapsed keeps it that way on return visits.
    document.querySelectorAll('details.lb-collapse[data-section]').forEach((det) => {
        const section = det.dataset.section;
        const key = 'lb-collapse:' + guildId + ':' + section;

        try {
            const stored = window.localStorage.getItem(key);
            if (stored === 'closed') det.open = false;
            else if (stored === 'open') det.open = true;
        } catch (_) { /* localStorage unavailable — ignore */ }

        det.addEventListener('toggle', () => {
            try {
                window.localStorage.setItem(key, det.open ? 'open' : 'closed');
            } catch (_) { /* ignore */ }
        });
    });

    // ---- Section tabs (Members / TobyCoin / Games) ----
    // Toggles which `<div class="lb-tab-panel">` is visible. Selection is
    // persisted per guild — same key-namespace style as the collapse memory.
    const tabButtons = Array.from(document.querySelectorAll('.lb-section-tabs [data-tab]'));
    const tabPanels = Array.from(document.querySelectorAll('.lb-tab-panel[data-tab-panel]'));
    if (tabButtons.length && tabPanels.length) {
        const tabKey = 'lb-tab:' + guildId;
        const validTabs = new Set(tabButtons.map((b) => b.dataset.tab));

        const showTab = (name) => {
            tabButtons.forEach((b) => {
                const active = b.dataset.tab === name;
                b.classList.toggle('active', active);
                b.setAttribute('aria-selected', active ? 'true' : 'false');
            });
            tabPanels.forEach((p) => {
                p.hidden = p.dataset.tabPanel !== name;
            });
        };

        let initial = 'members';
        try {
            const stored = window.localStorage.getItem(tabKey);
            if (stored && validTabs.has(stored)) initial = stored;
        } catch (_) { /* ignore */ }
        showTab(initial);

        tabButtons.forEach((b) => {
            b.addEventListener('click', () => {
                const name = b.dataset.tab;
                showTab(name);
                try { window.localStorage.setItem(tabKey, name); } catch (_) { /* ignore */ }
            });
        });
    }

    // ---- Contributor tooltips ----
    // Show on hover/focus/click, hide on blur/leave/Escape/click-outside.
    // The tooltip already lives in the DOM (server-rendered) so screen readers
    // see it via aria-describedby; this controller only flips [hidden].
    const tooltipHosts = Array.from(document.querySelectorAll('.lb-tooltip-host'));
    let openTooltip = null;
    const closeTooltip = (tip) => {
        if (!tip) return;
        tip.hidden = true;
        tip.classList.remove('lb-tooltip-flipped');
        if (openTooltip === tip) openTooltip = null;
    };
    const openTooltipFor = (host) => {
        const tip = host.querySelector('.lb-tooltip');
        if (!tip) return;
        if (openTooltip && openTooltip !== tip) closeTooltip(openTooltip);
        tip.hidden = false;
        openTooltip = tip;
        // Flip above the row if it would overflow the viewport bottom.
        // Skip the flip check on mobile widths where the tooltip is pinned
        // to the bottom of the viewport via CSS anyway.
        if (window.innerWidth > 600) {
            const rect = tip.getBoundingClientRect();
            if (rect.bottom > window.innerHeight) {
                tip.classList.add('lb-tooltip-flipped');
            }
        }
    };

    tooltipHosts.forEach((host) => {
        if (!host.querySelector('.lb-tooltip')) return;
        host.addEventListener('mouseenter', () => openTooltipFor(host));
        host.addEventListener('mouseleave', () => {
            // Don't close on mouseleave if the host has focus — keyboard users
            // need the tooltip to persist until blur.
            if (document.activeElement !== host) {
                closeTooltip(host.querySelector('.lb-tooltip'));
            }
        });
        host.addEventListener('focusin', () => openTooltipFor(host));
        host.addEventListener('focusout', (e) => {
            // focusout fires before relatedTarget is set in some browsers;
            // schedule the close so a sibling click inside the tooltip can land.
            setTimeout(() => {
                if (!host.contains(document.activeElement)) {
                    closeTooltip(host.querySelector('.lb-tooltip'));
                }
            }, 0);
        });
        host.addEventListener('click', (e) => {
            const tip = host.querySelector('.lb-tooltip');
            if (tip.hidden) openTooltipFor(host);
            else closeTooltip(tip);
            e.stopPropagation();
        });
        host.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                const tip = host.querySelector('.lb-tooltip');
                if (tip.hidden) openTooltipFor(host);
                else closeTooltip(tip);
            }
        });
    });

    document.addEventListener('click', () => closeTooltip(openTooltip));
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeTooltip(openTooltip);
    });

    // ===================================================================
    // Client-side sort for the Members tab.
    //
    // The sort chips stay as <a href> for the no-JS case (full page reload
    // re-runs the server-side sort on table + podium). When JS is on we
    // hijack the click and re-order the standings <tbody> in place. The
    // URL stays untouched and the podium (rendered for the URL's sort
    // initially) is not re-sorted — treat chip-click as "re-order the
    // table I'm looking at", not "navigate to a different view".
    // Refreshing the page restores the canonical URL-driven sort.
    // ===================================================================
    const sortNav = document.querySelector('.lb-sort');
    const tbody = document.querySelector('.lb-standings-table tbody');
    if (sortNav && tbody) {
        const valueAttr = {
            month: 'data-credits-month',
            lifetime: 'data-credits-total',
            xp: 'data-xp',
        };
        const rankClass = (rank) =>
            rank === 1 ? 'lb-rank-1'
                : rank === 2 ? 'lb-rank-2'
                    : rank === 3 ? 'lb-rank-3'
                        : '';

        function applySort(sortKey) {
            const attr = valueAttr[sortKey];
            if (!attr) return;

            // Stable numeric-desc sort. Detach, sort, re-append.
            const rows = Array.from(tbody.querySelectorAll('tr'));
            rows.sort((a, b) => {
                const av = Number(a.getAttribute(attr)) || 0;
                const bv = Number(b.getAttribute(attr)) || 0;
                return bv - av;
            });
            const frag = document.createDocumentFragment();
            rows.forEach((tr, i) => {
                const newRank = i + 1;
                const rankEl = tr.querySelector('.lb-rank');
                if (rankEl) {
                    rankEl.textContent = String(newRank);
                    rankEl.classList.remove('lb-rank-1', 'lb-rank-2', 'lb-rank-3');
                    const cls = rankClass(newRank);
                    if (cls) rankEl.classList.add(cls);
                }
                frag.appendChild(tr);
            });
            tbody.appendChild(frag);

            // Toggle the active state on the chips so the visible
            // selection follows the user's click.
            sortNav.querySelectorAll('.lb-sort-option').forEach((c) => {
                const isActive = c.dataset.sort === sortKey;
                c.classList.toggle('active', isActive);
                c.setAttribute('aria-selected', isActive ? 'true' : 'false');
            });
        }

        sortNav.querySelectorAll('.lb-sort-option').forEach((chip) => {
            chip.addEventListener('click', (e) => {
                const sortKey = chip.dataset.sort;
                if (!sortKey || !valueAttr[sortKey]) return; // fall through to href
                e.preventDefault();
                applySort(sortKey);
            });
        });
    }

    // ---- Member search (Members tab) ----
    // The full ranked standings are all in the DOM, so filtering client-side
    // searches every member — not just the visible top rows. Degrades fine
    // with JS off (no input shown does nothing; the table is still complete).
    const searchInput = document.getElementById('lb-member-search');
    const standingsBody = document.getElementById('lb-standings-body');
    if (searchInput && standingsBody) {
        const emptyEl = document.querySelector('.lb-search-empty');
        const termEl = document.querySelector('.lb-search-term');
        searchInput.addEventListener('input', () => {
            const query = searchInput.value;
            const rows = Array.from(standingsBody.querySelectorAll('tr'));
            const visible = filterStandingsRows(rows, query);
            if (termEl) termEl.textContent = query.trim();
            if (emptyEl) emptyEl.hidden = visible !== 0 || query.trim() === '';
        });
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { filterStandingsRows };
}
