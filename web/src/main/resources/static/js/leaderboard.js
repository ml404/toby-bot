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
})();
