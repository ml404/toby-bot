const { toggleNav, markActiveNavItem } = require('../../main/resources/static/js/home');

// ---------------------------------------------------------------------------
// toggleNav
// ---------------------------------------------------------------------------

describe('toggleNav (minimal DOM — only #nav-menu)', () => {
    beforeEach(() => {
        document.body.innerHTML = `<div id="nav-menu"></div>`;
        document.body.className = '';
    });

    test('adds "open" class and returns true when menu is closed', () => {
        const result = toggleNav();

        expect(result).toBe(true);
        expect(document.getElementById('nav-menu').classList.contains('open')).toBe(true);
    });

    test('removes "open" class and returns false when menu is already open', () => {
        const menu = document.getElementById('nav-menu');
        menu.classList.add('open');

        const result = toggleNav();

        expect(result).toBe(false);
        expect(menu.classList.contains('open')).toBe(false);
    });

    test('toggles open twice to return to closed state', () => {
        toggleNav(); // open
        const result = toggleNav(); // close

        expect(result).toBe(false);
        expect(document.getElementById('nav-menu').classList.contains('open')).toBe(false);
    });

    test('returns false when nav-menu element does not exist', () => {
        document.body.innerHTML = '';

        const result = toggleNav();

        expect(result).toBe(false);
    });
});

describe('toggleNav (drawer DOM — toggle + backdrop + body)', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <button id="nav-toggle" class="nav-toggle" aria-expanded="false"></button>
            <div id="nav-menu" class="nav-links"></div>
            <div id="nav-backdrop" class="nav-backdrop" hidden></div>
        `;
        document.body.className = '';
    });

    test('opens: flips aria-expanded, reveals backdrop, locks body scroll', () => {
        const result = toggleNav();

        expect(result).toBe(true);
        expect(document.getElementById('nav-toggle').getAttribute('aria-expanded')).toBe('true');
        expect(document.getElementById('nav-backdrop').hasAttribute('hidden')).toBe(false);
        expect(document.body.classList.contains('nav-open')).toBe(true);
    });

    test('closes: restores aria-expanded, hides backdrop, unlocks body scroll', () => {
        toggleNav(); // open
        const result = toggleNav(); // close

        expect(result).toBe(false);
        expect(document.getElementById('nav-toggle').getAttribute('aria-expanded')).toBe('false');
        expect(document.getElementById('nav-backdrop').hasAttribute('hidden')).toBe(true);
        expect(document.body.classList.contains('nav-open')).toBe(false);
    });
});

// ---------------------------------------------------------------------------
// markActiveNavItem
// ---------------------------------------------------------------------------

describe('markActiveNavItem', () => {
    beforeEach(() => {
        document.body.innerHTML = `
            <div id="nav-menu">
                <a class="nav-item" href="/profile/guilds">Profile</a>
                <a class="nav-item" href="/leaderboards">Leaderboards</a>
                <div class="nav-dropdown">
                    <button class="nav-dropdown-toggle">Play</button>
                    <div class="nav-dropdown-menu">
                        <a href="/casino/guilds?game=slots">Slots</a>
                        <a href="/poker/guilds">Poker</a>
                    </div>
                </div>
            </div>
        `;
    });

    test('marks the matching top-level nav item', () => {
        history.pushState({}, '', '/leaderboards');
        markActiveNavItem();
        const links = document.querySelectorAll('.nav-item');
        expect(links[0].hasAttribute('aria-current')).toBe(false);
        expect(links[1].getAttribute('aria-current')).toBe('page');
    });

    test('marks the matching dropdown item and tags the parent dropdown', () => {
        history.pushState({}, '', '/casino/guilds');
        markActiveNavItem();
        const slots = document.querySelector('a[href="/casino/guilds?game=slots"]');
        expect(slots.getAttribute('aria-current')).toBe('page');
        const parent = document.querySelector('.nav-dropdown');
        expect(parent.classList.contains('is-active-parent')).toBe(true);
    });

    test('matches a longer path under the link (e.g. /profile/guilds/123 → Profile)', () => {
        history.pushState({}, '', '/profile/guilds/12345');
        markActiveNavItem();
        const profile = document.querySelector('a[href="/profile/guilds"]');
        expect(profile.getAttribute('aria-current')).toBe('page');
    });

    test('is a no-op when no link matches', () => {
        history.pushState({}, '', '/totally-unrelated-page');
        markActiveNavItem();
        expect(document.querySelector('[aria-current="page"]')).toBeNull();
    });
});
