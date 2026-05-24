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
const NAV_CSS = path.resolve(
    __dirname,
    '../../main/resources/static/css/nav.css'
);

// Extracts a CSS rule body (everything between `selector {` and the
// matching closing brace) so we can assert on the declarations without
// being brittle about whitespace or sibling rule order. Returns null if
// the selector isn't found.
function extractRuleBody(css, selectorPrefix) {
    const start = css.indexOf(selectorPrefix);
    if (start < 0) return null;
    const braceOpen = css.indexOf('{', start);
    if (braceOpen < 0) return null;
    let depth = 1;
    let i = braceOpen + 1;
    while (i < css.length && depth > 0) {
        const ch = css[i];
        if (ch === '{') depth += 1;
        else if (ch === '}') {
            depth -= 1;
            if (depth === 0) return css.slice(braceOpen + 1, i);
        }
        i += 1;
    }
    return null;
}

// Extracts the contents of the .nav-links container by walking forward
// from its opening tag and balancing <div>/</div> pairs. Anchoring on a
// sibling element (like the old .nav-toggle) is brittle — the toggle now
// sits BEFORE .nav-links in the DOM. This walker only cares about the
// .nav-links subtree.
function extractNavLinksInner(html) {
    const openTag = '<div class="nav-links"';
    const start = html.indexOf(openTag);
    if (start < 0) return null;
    const contentStart = html.indexOf('>', start) + 1;
    let depth = 1;
    let i = contentStart;
    while (i < html.length && depth > 0) {
        const nextOpen = html.indexOf('<div', i);
        const nextClose = html.indexOf('</div>', i);
        if (nextClose < 0) return null;
        if (nextOpen >= 0 && nextOpen < nextClose) {
            depth += 1;
            i = nextOpen + 4;
        } else {
            depth -= 1;
            if (depth === 0) return html.slice(contentStart, nextClose);
            i = nextClose + 6;
        }
    }
    return null;
}

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

    test('hamburger is exposed to assistive tech (aria-controls, aria-expanded)', () => {
        // After the redesign the toggle drives a fixed-position drawer; the
        // attributes let screen readers announce open/closed state and
        // hop directly to the menu it controls.
        const toggleMatch = html.match(/<button[^>]*class="[^"]*\bnav-toggle\b[^"]*"[^>]*>/);
        expect(toggleMatch).not.toBeNull();
        expect(toggleMatch[0]).toMatch(/aria-controls="nav-menu"/);
        expect(toggleMatch[0]).toMatch(/aria-expanded="false"/);
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

    test('renders a backdrop element wired to toggleNav()', () => {
        // The drawer needs a dim/tap-outside-to-close backdrop on mobile.
        // It lives outside .nav-links so it's not part of the panel itself.
        expect(html).toMatch(
            /<div[^>]*class="[^"]*\bnav-backdrop\b[^"]*"[^>]*id="nav-backdrop"[^>]*onclick="toggleNav\(\)"/
        );
    });

    test('renders a drawer header with a close button wired to toggleNav()', () => {
        expect(html).toMatch(/class="[^"]*\bnav-drawer-header\b/);
        expect(html).toMatch(
            /<button[^>]*class="[^"]*\bnav-drawer-close\b[^"]*"[^>]*onclick="toggleNav\(\)"/
        );
    });

    test('groups links into labelled sections (Main / Casino / PvP / Economy / Social / Tools / Moderation)', () => {
        // Each .nav-section block carries a .nav-section-eyebrow with the
        // section name; on desktop those eyebrows are display:none, on
        // mobile they group the items inside the drawer.
        const expected = ['Main', 'Casino', 'PvP', 'Economy', 'Social', 'Tools', 'Moderation'];
        for (const label of expected) {
            const re = new RegExp(
                `<div[^>]*class="[^"]*\\bnav-section-eyebrow\\b[^"]*"[^>]*>\\s*${label}\\s*</div>`
            );
            expect(html).toMatch(re);
        }
    });

    test('exposes an auth footer with either Login or Logout', () => {
        expect(html).toMatch(/class="[^"]*\bnav-auth\b/);
        // Login link (signed-out) and Logout form (signed-in) both live in
        // the auth footer.
        expect(html).toMatch(/href="\/oauth2\/authorization\/discord"[^>]*class="[^"]*\bbtn-discord\b/);
        expect(html).toMatch(/<button[^>]*type="submit"[^>]*class="[^"]*\bbtn-logout\b[^"]*"/);
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

    test('Casino dropdown lists every minigame and points at the picker with a game query', () => {
        // Each entry carries `?game=<slug>` so the picker can deep-link past
        // itself when the user only shares one server with the bot (or has
        // anchored one as default). Without the slug the picker still
        // renders and acts as the per-guild game index.
        const casino = html.match(
            /<div class="nav-dropdown">[\s\S]*?Casino[\s\S]*?<\/div>\s*<\/div>/
        );
        expect(casino).not.toBeNull();
        expect(casino[0]).toMatch(/href="\/casino\/guilds\?game=slots"[^>]*>[^<]*Slots/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds\?game=coinflip"[^>]*>[^<]*Coinflip/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds\?game=dice"[^>]*>[^<]*Dice/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds\?game=highlow"[^>]*>[^<]*High-Low/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds\?game=scratch"[^>]*>[^<]*Scratch/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds\?game=keno"[^>]*>[^<]*Keno/);
        expect(casino[0]).toMatch(/href="\/casino\/guilds\?game=baccarat"[^>]*>[^<]*Baccarat/);
        // Coming-soon placeholder is gone now that the games ship.
        expect(casino[0]).not.toMatch(/coming soon/i);
    });

    test('Market and Titles are no longer top-level nav-links siblings', () => {
        // Regression: if someone re-introduces them at the top level the
        // navbar grows by two items and the dropdown becomes redundant.
        const inner = extractNavLinksInner(html);
        expect(inner).not.toBeNull();
        // Market / Titles links exist (inside dropdowns) but not as direct
        // children of nav-links — strip out the dropdown subtrees first.
        const stripped = inner.replace(/<div class="nav-dropdown">[\s\S]*?<\/div>\s*<\/div>/g, '');
        expect(stripped).not.toMatch(/href="\/economy\/guilds"/);
        expect(stripped).not.toMatch(/href="\/titles\/guilds"/);
    });
});

// ---------------------------------------------------------------------------
// CSS regression tests
//
// The follow-up fix in this PR restored desktop visibility of the Login
// button + default-guild pill, and de-duplicated the close-✕ glyph that
// rendered on top of the hamburger when the mobile drawer was open. These
// tests pin those contracts so a future restyle can't silently revert
// either regression.
// ---------------------------------------------------------------------------

describe('nav.css desktop visibility', () => {
    let css;

    beforeAll(() => {
        css = fs.readFileSync(NAV_CSS, 'utf8');
    });

    test('.nav-identity is not display:none at top level', () => {
        // Locate the top-level .nav-identity rule body and assert it's
        // not hidden. The mobile drawer rule lives inside @media and is
        // a different rule body.
        const body = extractRuleBody(css, '\n.nav-identity {');
        expect(body).not.toBeNull();
        expect(body).not.toMatch(/display:\s*none/);
    });

    test('.nav-auth is not display:none at top level', () => {
        const body = extractRuleBody(css, '\n.nav-auth {');
        expect(body).not.toBeNull();
        expect(body).not.toMatch(/display:\s*none/);
    });

    test('.nav-identity and .nav-auth are not bundled into the desktop hide list', () => {
        // The original regression was a single rule like
        //   .nav-drawer-header, .nav-identity, .nav-auth { display: none; }
        // that hid all three on desktop. Catch it by name — the drawer
        // header is allowed (mobile-only), the other two must not appear.
        const hideListPattern = /\.nav-drawer-header[\s\S]{0,200}?display:\s*none/;
        const hideListMatch = css.match(hideListPattern);
        if (hideListMatch) {
            expect(hideListMatch[0]).not.toMatch(/\.nav-identity\b/);
            expect(hideListMatch[0]).not.toMatch(/\.nav-auth\b/);
        }
    });

    test('drawer-only chrome (.nav-drawer-header) stays hidden on desktop', () => {
        // Companion to the rule above: the drawer header *should* be
        // hidden on desktop. Asserts the dedupe didn't accidentally
        // unhide it.
        expect(css).toMatch(/\.nav-drawer-header[\s\S]{0,80}?display:\s*none/);
    });
});

describe('nav.css mobile drawer-close dedupe', () => {
    let css;

    beforeAll(() => {
        css = fs.readFileSync(NAV_CSS, 'utf8');
    });

    test('hides the hamburger while the drawer is open', () => {
        // Without this rule the hamburger swaps glyph to ✕ via
        // [aria-expanded="true"] and stacks visually on top of the
        // drawer's own sticky-header close button — two ✕ marks
        // overlapping. The drawer's ✕ is the canonical dismissal
        // affordance once open.
        expect(css).toMatch(
            /body\.nav-open\s+\.nav-toggle\s*\{\s*display:\s*none\s*;?\s*\}/
        );
    });

    test('hamburger remains visible by default (no global hide)', () => {
        // Defensive check: the hide rule must be scoped to body.nav-open,
        // not applied unconditionally. A naked `.nav-toggle { display: none }`
        // would leave the closed-state navbar with no way to open the menu.
        const mobileBlockStart = css.indexOf('@media (max-width: 600px)');
        expect(mobileBlockStart).toBeGreaterThan(-1);
        const mobileBlock = css.slice(mobileBlockStart);
        // The mobile rule for .nav-toggle should set display: inline-flex
        // (or block / flex) — anything but `none`.
        expect(mobileBlock).toMatch(
            /\n\s*\.nav-toggle\s*\{[^}]*display:\s*(?:inline-flex|flex|block|inline-block)/
        );
    });
});
