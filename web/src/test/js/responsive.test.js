/*
 * Responsive contract tests.
 *
 * jsdom doesn't apply media queries to layout — but it does parse CSS and
 * we can read the source files directly. This suite enforces three things:
 *
 *  1. base.css declares the canonical breakpoint tokens + tap-min token.
 *  2. Every per-page CSS file uses only the four canonical breakpoint
 *     values (768 / 600 / 480 / 380). Stylelint also enforces this, but
 *     having a Jest test means the contract is checked in `npm test`
 *     even when the linter is skipped.
 *  3. The shared mobile data-label fallback is present (so a missing
 *     `data-label` on a `<td>` won't silently render unlabeled on phone).
 */
const fs = require('fs');
const path = require('path');

const CSS_DIR = path.resolve(__dirname, '../../main/resources/static/css');
const CANONICAL_BREAKPOINTS = ['768px', '600px', '480px', '380px'];
const MAX_WIDTH_RE = /@media\s*\([^)]*max-width:\s*([0-9]+px)/g;

function listCssFiles() {
    return fs.readdirSync(CSS_DIR)
        .filter(f => f.endsWith('.css'))
        .map(f => path.join(CSS_DIR, f));
}

describe('responsive contract', () => {
    let baseCss;
    beforeAll(() => {
        baseCss = fs.readFileSync(path.join(CSS_DIR, 'base.css'), 'utf8');
    });

    test('base.css declares all four canonical breakpoint tokens', () => {
        for (const token of ['--bp-tablet:', '--bp-mobile:', '--bp-small:', '--bp-tiny:']) {
            expect(baseCss).toContain(token);
        }
    });

    test('base.css declares --tap-min for touch targets', () => {
        expect(baseCss).toMatch(/--tap-min:\s*44px/);
    });

    test('touch-target media block consumes --tap-min', () => {
        // The `(hover: none) and (pointer: coarse)` block applies tap-min
        // to button classes — guard against the variable being defined
        // but unused.
        const touchBlock = baseCss.match(
            /@media \(hover: none\)[\s\S]*?\n}/
        );
        expect(touchBlock).not.toBeNull();
        expect(touchBlock[0]).toMatch(/var\(--tap-min\)/);
    });

    test('mobile data-label fallback is registered', () => {
        // Catches a future template that forgets data-label by falling
        // back to data-col instead of rendering a naked value cell.
        expect(baseCss).toMatch(
            /td:not\(\[data-label\]\)\[data-col\]::before/
        );
    });

    test('shared grid utilities are defined', () => {
        for (const cls of ['.grid-auto', '.grid-2-mobile', '.grid-3-mobile', '.grid-4-mobile', '.stack-mobile']) {
            expect(baseCss).toContain(cls);
        }
    });

    test('every @media (max-width:) uses a canonical breakpoint', () => {
        const violations = [];
        for (const file of listCssFiles()) {
            const src = fs.readFileSync(file, 'utf8');
            let match;
            while ((match = MAX_WIDTH_RE.exec(src)) !== null) {
                const bp = match[1];
                if (!CANONICAL_BREAKPOINTS.includes(bp)) {
                    violations.push(`${path.basename(file)}: ${bp}`);
                }
            }
        }
        expect(violations).toEqual([]);
    });

    test('.btn and .btn-primary share the same ruleset (not duplicated)', () => {
        // Confirms the dedupe — the file should declare them together
        // in a selector list, not as two separate identical blocks.
        const sharedSelector = baseCss.match(/\.btn,\s*\n\s*\.btn-primary\s*{/);
        expect(sharedSelector).not.toBeNull();
    });
});
