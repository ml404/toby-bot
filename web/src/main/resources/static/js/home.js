// Mobile nav drawer + dropdown wiring.
//
// toggleNav() opens/closes the right-slide drawer on mobile. On desktop the
// .nav-links container is laid out inline and the drawer chrome (header,
// backdrop, scroll-lock) is never visible — adding/removing the .open class
// is harmless because the CSS rules that activate the drawer behaviour are
// inside @media (max-width: 600px).
//
// The function tolerates a minimal DOM (just #nav-menu) so the existing
// home.test.js fixtures keep working — every secondary element lookup is
// optional.

let lastFocusedBeforeNav = null;

function toggleNav() {
    const menu = document.getElementById('nav-menu');
    if (!menu) return false;
    const willOpen = !menu.classList.contains('open');
    menu.classList.toggle('open', willOpen);

    const toggle = document.getElementById('nav-toggle')
        || document.querySelector('.nav-toggle');
    if (toggle) toggle.setAttribute('aria-expanded', willOpen ? 'true' : 'false');

    const backdrop = document.getElementById('nav-backdrop');
    if (backdrop) {
        if (willOpen) backdrop.removeAttribute('hidden');
        else backdrop.setAttribute('hidden', '');
    }

    if (document.body && document.body.classList) {
        document.body.classList.toggle('nav-open', willOpen);
    }

    if (willOpen) {
        lastFocusedBeforeNav = document.activeElement;
        const firstFocusable = menu.querySelector(
            '.nav-drawer-close, a, button, [tabindex]:not([tabindex="-1"])'
        );
        if (firstFocusable && typeof firstFocusable.focus === 'function') {
            try { firstFocusable.focus(); } catch (_) { /* jsdom focus may throw */ }
        }
    } else {
        closeAllDropdowns();
        if (lastFocusedBeforeNav && typeof lastFocusedBeforeNav.focus === 'function') {
            try { lastFocusedBeforeNav.focus(); } catch (_) { /* ignore */ }
        }
        lastFocusedBeforeNav = null;
    }

    return willOpen;
}

// Click-toggle for navbar dropdowns. Desktop also opens on :hover via CSS;
// click is the keyboard/touch path. Closing any other open dropdown keeps
// only one expanded at a time.
function toggleDropdown(toggle) {
    if (!toggle) return false;
    const dropdown = toggle.closest('.nav-dropdown');
    if (!dropdown) return false;
    const willOpen = !dropdown.classList.contains('open');
    closeAllDropdowns();
    if (willOpen) {
        dropdown.classList.add('open');
        toggle.setAttribute('aria-expanded', 'true');
    }
    return willOpen;
}

function closeAllDropdowns() {
    const open = document.querySelectorAll('.nav-dropdown.open');
    open.forEach(function (d) {
        d.classList.remove('open');
        const t = d.querySelector('.nav-dropdown-toggle');
        if (t) t.setAttribute('aria-expanded', 'false');
    });
}

// Mark the link matching the current URL with aria-current="page" and tag
// the enclosing .nav-dropdown so the parent button picks up the same accent.
// Longest-prefix wins so /casino/guilds?game=slots matches the Slots entry
// over the bare /casino path. Pure DOM; CSS does the styling.
function markActiveNavItem() {
    const menu = document.getElementById('nav-menu');
    if (!menu) return;
    const path = (typeof location !== 'undefined' && location.pathname) || '/';
    const links = menu.querySelectorAll('.nav-item, .nav-dropdown-menu a');
    let best = null;
    let bestLen = -1;
    links.forEach(function (a) {
        let href = a.getAttribute('href') || '';
        // Strip query + hash before comparing — query params (?game=slots)
        // are intent, not routing.
        const q = href.indexOf('?');
        if (q >= 0) href = href.slice(0, q);
        const h = href.indexOf('#');
        if (h >= 0) href = href.slice(0, h);
        if (!href || href === '/') return;
        const matches = path === href || path.indexOf(href + '/') === 0;
        if (matches && href.length > bestLen) {
            best = a;
            bestLen = href.length;
        }
    });
    if (!best) return;
    best.setAttribute('aria-current', 'page');
    const parentDropdown = best.closest('.nav-dropdown');
    if (parentDropdown) parentDropdown.classList.add('is-active-parent');
}

if (typeof document !== 'undefined' && document.addEventListener) {
    // Click outside any dropdown collapses them all. Clicks inside the
    // drawer container itself still bubble (so the drawer doesn't auto-close
    // when the user reaches for a dropdown trigger), but outside-drawer
    // clicks on the backdrop will close the drawer via the backdrop's own
    // onclick handler.
    document.addEventListener('click', function (e) {
        if (e.target && e.target.closest && e.target.closest('.nav-dropdown')) return;
        closeAllDropdowns();
    });
    // Esc closes any open dropdown, then the drawer if it's still open.
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') return;
        const anyDropdownOpen = document.querySelector('.nav-dropdown.open');
        if (anyDropdownOpen) {
            closeAllDropdowns();
            return;
        }
        const menu = document.getElementById('nav-menu');
        if (menu && menu.classList.contains('open')) toggleNav();
    });
    document.addEventListener('DOMContentLoaded', markActiveNavItem);
    // Resizing past the mobile breakpoint while the drawer is open would
    // leave a fixed-position panel hanging over the desktop layout — close
    // it pre-emptively. matchMedia is feature-detected for jsdom safety.
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
        try {
            const mq = window.matchMedia('(min-width: 601px)');
            const handler = function (ev) {
                if (!ev.matches) return;
                const menu = document.getElementById('nav-menu');
                if (menu && menu.classList.contains('open')) toggleNav();
            };
            if (typeof mq.addEventListener === 'function') mq.addEventListener('change', handler);
            else if (typeof mq.addListener === 'function') mq.addListener(handler);
        } catch (_) { /* matchMedia unavailable — ignore */ }
    }
}

if (typeof module !== 'undefined') {
    module.exports = { toggleNav, toggleDropdown, closeAllDropdowns, markActiveNavItem };
}
