const fs = require('fs');
const path = require('path');

// Locks in the fix for the mobile hamburger menu: the navbar fragment must
// ship its own script dependency so every page that imports the fragment
// gets a working toggleNav(), regardless of which page-specific scripts the
// page itself loads.
//
// Previously toggleNav() lived in home.js and only ~10 of 18 pages loaded
// it, so the hamburger rendered but did nothing on the other pages.

const NAVBAR_FRAGMENT = path.resolve(
    __dirname,
    '../../main/resources/templates/fragments/navbar.html'
);

describe('navbar fragment', () => {
    let html;

    beforeAll(() => {
        html = fs.readFileSync(NAVBAR_FRAGMENT, 'utf8');
    });

    test('declares a Thymeleaf fragment named "navbar"', () => {
        expect(html).toMatch(/th:fragment\s*=\s*"navbar"/);
    });

    test('renders a hamburger toggle button wired to toggleNav()', () => {
        expect(html).toMatch(
            /<button[^>]*class="[^"]*\bnav-toggle\b[^"]*"[^>]*onclick="toggleNav\(\)"/
        );
    });

    test('renders the collapsible menu container with id="nav-menu"', () => {
        expect(html).toMatch(/id="nav-menu"/);
        expect(html).toMatch(/class="[^"]*\bnav-links\b[^"]*"[^>]*id="nav-menu"/);
    });

    test('includes the home.js script so toggleNav() is defined wherever the fragment is used', () => {
        expect(html).toMatch(
            /<script[^>]*th:src\s*=\s*"@\{\s*\/js\/home\.js\s*}"[^>]*>\s*<\/script>/
        );
    });

    test('places the script inside the navbar fragment element', () => {
        const fragmentMatch = html.match(
            /<nav\s+th:fragment="navbar"[\s\S]*?<\/nav>/
        );
        expect(fragmentMatch).not.toBeNull();
        expect(fragmentMatch[0]).toMatch(
            /<script[^>]*th:src\s*=\s*"@\{\s*\/js\/home\.js\s*}"/
        );
    });

    // PR 2 (navbar dropdowns): Economy folds Market + Titles; Casino is
    // the home for the upcoming /slots minigame. Both must render as
    // <button class="nav-dropdown-toggle"> with their menu links inside
    // a sibling .nav-dropdown-menu — anything else breaks the CSS
    // selectors that show/hide the menu on hover/click.
    test('Economy dropdown contains the Market and Titles links', () => {
        const economy = html.match(
            /<div class="nav-dropdown">[\s\S]*?Economy[\s\S]*?<\/div>\s*<\/div>/
        );
        expect(economy).not.toBeNull();
        expect(economy[0]).toMatch(/href="\/economy\/guilds"[^>]*>\s*Market\s*</);
        expect(economy[0]).toMatch(/href="\/titles\/guilds"[^>]*>\s*Titles\s*</);
        expect(economy[0]).toMatch(
            /<button[^>]*class="[^"]*\bnav-dropdown-toggle\b[^"]*"[^>]*onclick="toggleDropdown\(this\)"/
        );
    });

    test('Casino dropdown lists every minigame and points at the picker', () => {
        const casino = html.match(
            /<div class="nav-dropdown">[\s\S]*?Casino[\s\S]*?<\/div>\s*<\/div>/
        );
        expect(casino).not.toBeNull();
        expect(casino[0]).toMatch(/href="\/casino\/guilds"[^>]*>[^<]*Slots/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds"[^>]*>[^<]*Coinflip/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds"[^>]*>[^<]*Dice/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds"[^>]*>[^<]*High-Low/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds"[^>]*>[^<]*Scratch/);
        // Coming-soon placeholder is gone now that the games ship.
        expect(casino[0]).not.toMatch(/coming soon/i);
    });

    test('Market and Titles are no longer top-level nav-links siblings', () => {
        // Regression: if someone re-introduces them at the top level the
        // navbar grows by two items and the dropdown becomes redundant.
        const navLinks = html.match(
            /<div class="nav-links" id="nav-menu">([\s\S]*?)<button class="nav-toggle"/
        );
        expect(navLinks).not.toBeNull();
        const topLevel = navLinks[1];
        // Market / Titles links exist (inside dropdowns) but not as direct
        // children of nav-links — strip out the dropdown subtrees first.
        const stripped = topLevel.replace(/<div class="nav-dropdown">[\s\S]*?<\/div>\s*<\/div>/g, '');
        expect(stripped).not.toMatch(/href="\/economy\/guilds"/);
        expect(stripped).not.toMatch(/href="\/titles\/guilds"/);
    });
});
