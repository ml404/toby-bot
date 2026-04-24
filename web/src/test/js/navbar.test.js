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
});
