/*
 * leaderboard.js controller tests.
 *
 * The file is an IIFE (no exports), so each test sets up the DOM it needs
 * and re-runs the IIFE source against the JSDOM document.
 */
const fs = require('fs');
const path = require('path');

const SRC = fs.readFileSync(
    path.resolve(__dirname, '../../main/resources/static/js/leaderboard.js'),
    'utf8'
);

const runScript = () => {
    // The script reads `document` / `window` from the JSDOM globals.
    // Wrapping in `Function` is enough — the IIFE inside SRC fires on eval.
    new Function(SRC)();
};

const baseFixture = `
    <main data-guild-id="42">
      <nav class="lb-section-tabs">
        <button data-tab="members" class="lb-sort-option active" aria-selected="true">
          <span class="lb-section-tab-label">Members</span>
          <span class="lb-section-tab-count">23 members</span>
        </button>
        <button data-tab="tobycoins" class="lb-sort-option" aria-selected="false">
          <span class="lb-section-tab-label">TobyCoin</span>
          <span class="lb-section-tab-count">10 holders</span>
        </button>
        <button data-tab="topgames" class="lb-sort-option" aria-selected="false">
          <span class="lb-section-tab-label">Games</span>
          <span class="lb-section-tab-count">10 games</span>
        </button>
      </nav>
      <div class="lb-tab-panel" data-tab-panel="members">members content</div>
      <div class="lb-tab-panel" data-tab-panel="tobycoins" hidden>tobycoin content</div>
      <div class="lb-tab-panel" data-tab-panel="topgames" hidden>
        <table>
          <tr>
            <td>
              <div class="lb-tooltip-host" tabindex="0">
                <span class="lb-name">Halo</span>
                <div class="lb-tooltip" role="tooltip" hidden>contributors</div>
              </div>
            </td>
          </tr>
        </table>
      </div>
    </main>
`;

// Same shape as baseFixture but without the TobyCoin tab — covers the
// live template's `th:if` branch that hides it when there are no holders.
const twoTabFixture = `
    <main data-guild-id="42">
      <nav class="lb-section-tabs">
        <button data-tab="members" class="lb-sort-option active" aria-selected="true">
          <span class="lb-section-tab-label">Members</span>
          <span class="lb-section-tab-count">5 members</span>
        </button>
        <button data-tab="topgames" class="lb-sort-option" aria-selected="false">
          <span class="lb-section-tab-label">Games</span>
          <span class="lb-section-tab-count">3 games</span>
        </button>
      </nav>
      <div class="lb-tab-panel" data-tab-panel="members">members content</div>
      <div class="lb-tab-panel" data-tab-panel="topgames" hidden>games content</div>
    </main>
`;

const setFixture = (html) => {
    document.body.innerHTML = html;
};

const clickEvent = () => new window.MouseEvent('click', { bubbles: true });
const mouseEnter = (el) => el.dispatchEvent(new window.Event('mouseenter'));
const mouseLeave = (el) => el.dispatchEvent(new window.Event('mouseleave'));
const focusIn = (el) => el.dispatchEvent(new window.FocusEvent('focusin', { bubbles: true }));
const keydown = (key, target = document) => target.dispatchEvent(new window.KeyboardEvent('keydown', { key, bubbles: true }));

describe('leaderboard.js — section tabs', () => {
    beforeEach(() => {
        setFixture(baseFixture);
        window.localStorage.clear();
    });

    test('defaults to the members tab when nothing is stored', () => {
        runScript();
        expect(document.querySelector('[data-tab-panel="members"]').hidden).toBe(false);
        expect(document.querySelector('[data-tab-panel="tobycoins"]').hidden).toBe(true);
        expect(document.querySelector('[data-tab-panel="topgames"]').hidden).toBe(true);
    });

    test('reads the stored tab and applies it on init', () => {
        window.localStorage.setItem('lb-tab:42', 'topgames');
        runScript();
        expect(document.querySelector('[data-tab-panel="members"]').hidden).toBe(true);
        expect(document.querySelector('[data-tab-panel="topgames"]').hidden).toBe(false);
    });

    test('ignores garbage values in storage and falls back to members', () => {
        window.localStorage.setItem('lb-tab:42', 'not-a-tab');
        runScript();
        expect(document.querySelector('[data-tab-panel="members"]').hidden).toBe(false);
    });

    test('clicking a tab switches the visible panel and persists', () => {
        runScript();
        document.querySelector('[data-tab="topgames"]').click();
        expect(document.querySelector('[data-tab-panel="topgames"]').hidden).toBe(false);
        expect(document.querySelector('[data-tab-panel="members"]').hidden).toBe(true);
        expect(window.localStorage.getItem('lb-tab:42')).toBe('topgames');
    });

    test('clicking a tab flips the aria-selected state', () => {
        runScript();
        document.querySelector('[data-tab="tobycoins"]').click();
        expect(document.querySelector('[data-tab="members"]').getAttribute('aria-selected')).toBe('false');
        expect(document.querySelector('[data-tab="tobycoins"]').getAttribute('aria-selected')).toBe('true');
    });

    test('clicks on inner spans still toggle the outer button via bubbling', () => {
        runScript();
        // Clicks on .lb-section-tab-label / .lb-section-tab-count should bubble
        // to the parent <button> the click handler is bound to. Guards against
        // a future refactor that moves the listener to the inner spans.
        const innerLabel = document.querySelector('[data-tab="topgames"] .lb-section-tab-label');
        innerLabel.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        expect(document.querySelector('[data-tab-panel="topgames"]').hidden).toBe(false);
        expect(document.querySelector('[data-tab="topgames"]').classList.contains('active')).toBe(true);
    });

    test('active class is toggled on the outer <button>, not the inner spans', () => {
        runScript();
        document.querySelector('[data-tab="topgames"]').click();
        const btn = document.querySelector('[data-tab="topgames"]');
        const label = btn.querySelector('.lb-section-tab-label');
        const count = btn.querySelector('.lb-section-tab-count');
        expect(btn.classList.contains('active')).toBe(true);
        expect(label.classList.contains('active')).toBe(false);
        expect(count.classList.contains('active')).toBe(false);
    });

    test('count badge text survives tab switching (JS does not clobber it)', () => {
        runScript();
        const beforeCount = document
            .querySelector('[data-tab="topgames"] .lb-section-tab-count')
            .textContent;
        document.querySelector('[data-tab="topgames"]').click();
        document.querySelector('[data-tab="members"]').click();
        const afterCount = document
            .querySelector('[data-tab="topgames"] .lb-section-tab-count')
            .textContent;
        expect(afterCount).toBe(beforeCount);
        expect(afterCount).toContain('games');
    });

    test('works when TobyCoin tab is conditionally hidden (2-tab layout)', () => {
        setFixture(twoTabFixture);
        window.localStorage.clear();
        runScript();
        // Only Members + Games. Click Games — its panel shows, Members hides.
        document.querySelector('[data-tab="topgames"]').click();
        expect(document.querySelector('[data-tab-panel="topgames"]').hidden).toBe(false);
        expect(document.querySelector('[data-tab-panel="members"]').hidden).toBe(true);
        expect(window.localStorage.getItem('lb-tab:42')).toBe('topgames');
        // Click back to Members.
        document.querySelector('[data-tab="members"]').click();
        expect(document.querySelector('[data-tab-panel="members"]').hidden).toBe(false);
        expect(window.localStorage.getItem('lb-tab:42')).toBe('members');
    });

    test('stored value pointing at the hidden TobyCoin tab is ignored in 2-tab layout', () => {
        // A user who last visited a guild with TobyCoin holders returns to a
        // guild that no longer has any. Their stored "tobycoins" preference
        // must not blow up — fall back to members.
        window.localStorage.setItem('lb-tab:42', 'tobycoins');
        setFixture(twoTabFixture);
        runScript();
        expect(document.querySelector('[data-tab-panel="members"]').hidden).toBe(false);
    });
});

describe('leaderboard.js — tooltip', () => {
    beforeEach(() => {
        setFixture(baseFixture);
        window.localStorage.clear();
        runScript();
        // Switch to the games tab so the tooltip host is in a visible panel.
        document.querySelector('[data-tab="topgames"]').click();
    });

    test('mouseenter on a host shows its tooltip', () => {
        const host = document.querySelector('.lb-tooltip-host');
        const tip = host.querySelector('.lb-tooltip');
        expect(tip.hidden).toBe(true);
        mouseEnter(host);
        expect(tip.hidden).toBe(false);
    });

    test('mouseleave hides the tooltip when host is not focused', () => {
        const host = document.querySelector('.lb-tooltip-host');
        const tip = host.querySelector('.lb-tooltip');
        mouseEnter(host);
        mouseLeave(host);
        expect(tip.hidden).toBe(true);
    });

    test('focusin opens the tooltip (keyboard nav)', () => {
        const host = document.querySelector('.lb-tooltip-host');
        const tip = host.querySelector('.lb-tooltip');
        focusIn(host);
        expect(tip.hidden).toBe(false);
    });

    test('Escape closes the open tooltip', () => {
        const host = document.querySelector('.lb-tooltip-host');
        const tip = host.querySelector('.lb-tooltip');
        mouseEnter(host);
        expect(tip.hidden).toBe(false);
        keydown('Escape');
        expect(tip.hidden).toBe(true);
    });

    test('clicking the host toggles the tooltip', () => {
        const host = document.querySelector('.lb-tooltip-host');
        const tip = host.querySelector('.lb-tooltip');
        host.dispatchEvent(clickEvent());
        expect(tip.hidden).toBe(false);
        host.dispatchEvent(clickEvent());
        expect(tip.hidden).toBe(true);
    });

    test('clicking outside the host closes an open tooltip', () => {
        const host = document.querySelector('.lb-tooltip-host');
        const tip = host.querySelector('.lb-tooltip');
        host.dispatchEvent(clickEvent());
        expect(tip.hidden).toBe(false);
        // Click somewhere not inside the host.
        document.body.dispatchEvent(clickEvent());
        expect(tip.hidden).toBe(true);
    });

    test('opening a second tooltip closes the first', () => {
        // Add a second host to the fixture.
        const panel = document.querySelector('[data-tab-panel="topgames"]');
        panel.insertAdjacentHTML('beforeend', `
            <div class="lb-tooltip-host" tabindex="0">
                <span class="lb-name">Tetris</span>
                <div class="lb-tooltip" role="tooltip" hidden>more contributors</div>
            </div>
        `);
        // The script was already initialized before this host existed, so we
        // need to re-run init. setFixture + runScript would clobber state, so
        // instead we attach listeners manually by re-running the IIFE — which
        // is what the prod page does on a full re-render.
        runScript();
        const hosts = document.querySelectorAll('.lb-tooltip-host');
        mouseEnter(hosts[0]);
        mouseEnter(hosts[1]);
        const tip0 = hosts[0].querySelector('.lb-tooltip');
        const tip1 = hosts[1].querySelector('.lb-tooltip');
        expect(tip0.hidden).toBe(true);
        expect(tip1.hidden).toBe(false);
    });
});

describe('leaderboard.js — robustness', () => {
    test('does nothing when the page has no main[data-guild-id]', () => {
        setFixture('<div>no main</div>');
        // Should not throw.
        expect(() => runScript()).not.toThrow();
    });

    test('does nothing when there are no tab buttons', () => {
        setFixture('<main data-guild-id="42"><div>just content</div></main>');
        expect(() => runScript()).not.toThrow();
    });
});
