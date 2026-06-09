// Settings tab: client-side config search filter, side-rail navigation,
// and the bulk "apply to all games" stake setter. The generic
// .config-row save handler lives in moderationCommon.js so it works on
// every page that has config rows; this file owns the settings-tab-only
// extras.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;

    // ---- Search filter -----------------------------------------------
    const input = document.getElementById('config-search');
    const count = document.getElementById('config-search-count');

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
    const pillars = Array.from(document.querySelectorAll('h2.config-pillar'));
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
            // Stake rows live inside a <tr>; hide the row too so the
            // table doesn't keep an empty <tr> behind when both cells
            // are filtered out.
            const tableRow = row.closest('tr');
            if (tableRow) {
                const stakeCells = tableRow.querySelectorAll('.config-row[data-key]');
                const anyVisible = Array.from(stakeCells).some(c => !c.classList.contains('is-hidden'));
                tableRow.classList.toggle('is-hidden', !anyVisible);
            }
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
        // Hide pillar headers whose sections are all filtered out so the
        // page doesn't show a bare "Casino" h2 with nothing under it. A
        // pillar "owns" every sibling .config-section up to the next
        // pillar; walk forward from each pillar and check whether any
        // owned section is still visible.
        pillars.forEach((pillar, idx) => {
            const nextPillar = pillars[idx + 1] || null;
            let anyOwnedVisible = false;
            for (let el = pillar.nextElementSibling; el && el !== nextPillar; el = el.nextElementSibling) {
                if (el.classList.contains('config-section') && !el.classList.contains('is-hidden')) {
                    anyOwnedVisible = true;
                    break;
                }
            }
            pillar.classList.toggle('is-hidden', !anyOwnedVisible && q !== '');
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

    if (input) {
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
        // /moderation/123/settings#search=jackpot). The rail-jump hash
        // (#settings-section-…) is consumed by the rail handler below
        // and intentionally skipped here.
        const initial = (window.location.hash.match(/^#search=(.*)$/) || [])[1];
        if (initial) {
            const decoded = decodeURIComponent(initial);
            input.value = decoded;
            snapshotIfNeeded();
            apply(decoded);
        }
    }

    // ---- Side-rail navigation ---------------------------------------
    // Each rail link is `href="#settings-section-<slug>"`. Click opens
    // the matching <details> (so the user lands at the section heading,
    // not at a closed accordion) and scrolls smoothly into view. An
    // IntersectionObserver mirrors the currently-visible section back
    // into the rail's active-link styling so the user has spatial
    // context while scrolling manually.
    const railLinks = Array.from(document.querySelectorAll('.settings-nav-link'));
    const sectionsById = new Map(
        sections
            .filter(s => s.id)
            .map(s => [s.id, s])
    );

    function openAndScroll(targetId, push) {
        const target = sectionsById.get(targetId);
        if (!target) return;
        target.open = true;
        // After-open layout is async (the chevron rotation runs); wait
        // a frame so scrollIntoView measures the expanded height.
        requestAnimationFrame(() => {
            target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
        if (push) {
            try {
                history.pushState(null, '', '#' + targetId);
            } catch (e) { /* ignore */ }
        }
        setActiveLink(targetId);
    }

    function setActiveLink(targetId) {
        railLinks.forEach(link => {
            const matches = link.getAttribute('href') === '#' + targetId;
            link.classList.toggle('is-active', matches);
            if (matches) link.setAttribute('aria-current', 'true');
            else link.removeAttribute('aria-current');
        });
    }

    railLinks.forEach(link => {
        link.addEventListener('click', (e) => {
            const href = link.getAttribute('href') || '';
            if (!href.startsWith('#settings-section-')) return;
            e.preventDefault();
            openAndScroll(href.slice(1), true);
        });
    });

    if (railLinks.length > 0 && 'IntersectionObserver' in window) {
        const visibility = new Map();
        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                visibility.set(entry.target.id, entry.intersectionRatio);
            });
            // Highlight the section with the highest visibility ratio
            // currently above the viewport's halfway mark.
            let bestId = null;
            let bestRatio = 0;
            visibility.forEach((ratio, id) => {
                if (ratio > bestRatio) {
                    bestRatio = ratio;
                    bestId = id;
                }
            });
            if (bestId && bestRatio > 0) setActiveLink(bestId);
        }, {
            // Trigger the highlight when the section is at least 25%
            // through the viewport so the rail tracks where the user
            // is actually reading, not just where a section first peeks
            // into view.
            rootMargin: '-25% 0px -50% 0px',
            threshold: [0, 0.25, 0.5, 0.75, 1],
        });
        sectionsById.forEach(section => observer.observe(section));
    }

    // Honour an initial `#settings-section-…` hash on page load (e.g.
    // a deep-link share). Run after the layout settles so scrollIntoView
    // gets the right offset.
    const railHashMatch = window.location.hash.match(/^#(settings-section-[a-z0-9-]+)$/);
    if (railHashMatch) {
        requestAnimationFrame(() => openAndScroll(railHashMatch[1], false));
    }

    // ---- Bulk "apply to all games" stake setter ---------------------
    // Reuses the per-row save endpoint — one POST per (game × min|max)
    // key, fanned out in parallel. Leaving either input blank skips
    // that side, so admins can bulk-set just minimums without
    // clobbering maximums.
    const bulkForm = document.querySelector('.settings-stakes-bulk');
    if (bulkForm) {
        const minInput = bulkForm.querySelector('input[name="bulkMin"]');
        const maxInput = bulkForm.querySelector('input[name="bulkMax"]');
        const btn = bulkForm.querySelector('button[type="submit"]');

        bulkForm.addEventListener('submit', (e) => {
            e.preventDefault();
            if (!ctx.isOwner) {
                toast('Only the server owner can change settings.', 'error');
                return;
            }
            const minVal = (minInput?.value || '').trim();
            const maxVal = (maxInput?.value || '').trim();
            if (minVal === '' && maxVal === '') {
                toast('Enter a minimum, a maximum, or both.', 'error');
                return;
            }

            // Gather every stake row in the table — `.settings-stake-cell`
            // is a hook added just for this lookup. Split into MIN / MAX
            // by the data-key suffix so the same cell loop drives both
            // sides.
            // Blackjack uses `_MIN_ANTE` / `_MAX_ANTE` keys instead of
            // `_MIN_STAKE` / `_MAX_STAKE` — semantically the same
            // per-hand bound, just named after the blackjack jargon.
            const stakeCells = Array.from(document.querySelectorAll('.settings-stake-cell[data-key]'));
            const isMinKey = (key) => key.endsWith('_MIN_STAKE') || key.endsWith('_MIN_ANTE');
            const isMaxKey = (key) => key.endsWith('_MAX_STAKE') || key.endsWith('_MAX_ANTE');
            const targets = [];
            stakeCells.forEach(cell => {
                const key = cell.dataset.key || '';
                if (minVal !== '' && isMinKey(key)) {
                    targets.push({ key: key, value: minVal, cell: cell });
                } else if (maxVal !== '' && isMaxKey(key)) {
                    targets.push({ key: key, value: maxVal, cell: cell });
                }
            });
            if (targets.length === 0) {
                toast('No matching stake rows found.', 'error');
                return;
            }

            const summary =
                'Apply ' +
                (minVal !== '' ? 'min=' + minVal : '') +
                (minVal !== '' && maxVal !== '' ? ', ' : '') +
                (maxVal !== '' ? 'max=' + maxVal : '') +
                ' to ' + targets.length + ' stake row' + (targets.length === 1 ? '' : 's') + '?';
            if (!confirm(summary)) return;

            btn.disabled = true;
            const guildPath = '/moderation/' + ctx.guildId + '/config';
            const requests = targets.map(t =>
                ctx.postJson(guildPath, { key: t.key, value: t.value })
                    .then(r => ({ target: t, ok: !!(r && r.ok), error: r?.error || null }))
                    .catch(err => ({ target: t, ok: false, error: (err && err.message) || 'Network error' }))
            );
            Promise.all(requests).then(results => {
                btn.disabled = false;
                let okCount = 0;
                let failCount = 0;
                results.forEach(res => {
                    const cellInput = res.target.cell.querySelector('input');
                    if (res.ok) {
                        okCount++;
                        if (cellInput) {
                            cellInput.value = res.target.value;
                            cellInput.defaultValue = res.target.value;
                        }
                    } else {
                        failCount++;
                    }
                });
                if (failCount === 0) {
                    toast('Updated ' + okCount + ' stake row' + (okCount === 1 ? '' : 's') + '.', 'success');
                    if (minInput) minInput.value = '';
                    if (maxInput) maxInput.value = '';
                } else if (okCount === 0) {
                    toast('All ' + failCount + ' updates failed.', 'error');
                } else {
                    toast(okCount + ' saved, ' + failCount + ' failed.', 'error');
                }
            });
        });
    }
})();
