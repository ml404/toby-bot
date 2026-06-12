// Pure-DOM unit tests for the price-watches rendering helpers exposed
// on window.TobyWatches. The IIFE at the bottom of economy-watches.js
// only wires DOM events, so the meaningful regression surface is the
// render functions: status classification, badge wiring per side,
// arrow direction, status pill colour class, and the empty-state
// toggle. These cover the cases the visual polish pass introduced
// (`fire-now`, `fired`, `is-inactive`, the `economy-watch-id` element).

const Watches = require('../../main/resources/static/js/economy-watches');

describe('TobyWatches.statusFor', () => {
    test('returns armed when enabled and price is on the same side', () => {
        const r = Watches.statusFor(
            { enabled: true, priceAtCreation: 100.0, threshold: 80.0, firedAt: null },
            currentPrice = 95.0
        );
        expect(r.cls).toBe('armed');
        expect(r.text).toBe('armed');
    });

    test('returns fire-now when current price has crossed the threshold', () => {
        // BUY-low: created at 100, threshold 80, price now 75 → crossed.
        const r = Watches.statusFor(
            { enabled: true, priceAtCreation: 100.0, threshold: 80.0, firedAt: null },
            75.0
        );
        expect(r.cls).toBe('fire-now');
        expect(r.text).toBe('would fire now');
    });

    test('fire-now also triggers on SELL-high crossings', () => {
        // SELL-high: created at 100, threshold 150, price now 160 → crossed.
        const r = Watches.statusFor(
            { enabled: true, priceAtCreation: 100.0, threshold: 150.0, firedAt: null },
            160.0
        );
        expect(r.cls).toBe('fire-now');
    });

    test('fire-now triggers when price equals threshold (boundary)', () => {
        const r = Watches.statusFor(
            { enabled: true, priceAtCreation: 100.0, threshold: 80.0, firedAt: null },
            80.0
        );
        expect(r.cls).toBe('fire-now');
    });

    test('returns fired with a formatted date when watch fired', () => {
        const firedAt = new Date('2026-01-15T12:00:00Z').getTime();
        const r = Watches.statusFor(
            { enabled: false, priceAtCreation: 100.0, threshold: 80.0, firedAt: firedAt },
            75.0
        );
        expect(r.cls).toBe('fired');
        expect(r.text.startsWith('fired ')).toBe(true);
    });

    test('returns disabled when manually disabled without firing', () => {
        const r = Watches.statusFor(
            { enabled: false, priceAtCreation: 100.0, threshold: 80.0, firedAt: null },
            95.0
        );
        expect(r.cls).toBe('disabled');
        expect(r.text).toBe('disabled');
    });

    test('falls back to armed when current price is unknown (null)', () => {
        const r = Watches.statusFor(
            { enabled: true, priceAtCreation: 100.0, threshold: 80.0, firedAt: null },
            null
        );
        expect(r.cls).toBe('armed');
    });
});

describe('TobyWatches.renderWatchRow', () => {
    const baseBuy = {
        id: 7,
        side: 'BUY',
        amount: 5,
        threshold: 80.0,
        priceAtCreation: 100.0,
        enabled: true,
        firedAt: null,
    };

    test('renders an <li> with watch-id dataset and economy-watch-row class', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 95.0);
        expect(li.tagName).toBe('LI');
        expect(li.classList.contains('economy-watch-row')).toBe(true);
        expect(li.dataset.watchId).toBe('7');
    });

    test('BUY watch gets economy-watch-side-buy on the side pill', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 95.0);
        const side = li.querySelector('.economy-watch-side');
        expect(side).not.toBeNull();
        expect(side.classList.contains('economy-watch-side-buy')).toBe(true);
        expect(side.classList.contains('economy-watch-side-sell')).toBe(false);
        expect(side.textContent).toBe('BUY 5');
    });

    test('SELL watch gets economy-watch-side-sell on the side pill', () => {
        const li = Watches.renderWatchRow(
            document,
            { ...baseBuy, side: 'SELL', amount: 3, threshold: 150.0 },
            120.0
        );
        const side = li.querySelector('.economy-watch-side');
        expect(side.classList.contains('economy-watch-side-sell')).toBe(true);
        expect(side.classList.contains('economy-watch-side-buy')).toBe(false);
        expect(side.textContent).toBe('SELL 3');
    });

    // The CSS row-stripe (.economy-watch-row.is-buy::before, .is-sell::before)
    // hooks off these flags. Regression guard: stripping either class
    // silently kills the buy/sell visual ID on the row.
    test('BUY watch tags the row with is-buy (for the side-stripe CSS hook)', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 95.0);
        expect(li.classList.contains('is-buy')).toBe(true);
        expect(li.classList.contains('is-sell')).toBe(false);
    });

    test('SELL watch tags the row with is-sell', () => {
        const li = Watches.renderWatchRow(
            document,
            { ...baseBuy, side: 'SELL', threshold: 150.0 },
            120.0
        );
        expect(li.classList.contains('is-sell')).toBe(true);
        expect(li.classList.contains('is-buy')).toBe(false);
    });

    test('threshold below priceAtCreation renders a down arrow in red class', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 95.0);
        const arrow = li.querySelector('.economy-watch-arrow');
        expect(arrow.textContent).toBe('↓');
        expect(arrow.classList.contains('economy-watch-arrow-down')).toBe(true);
    });

    test('threshold above priceAtCreation renders an up arrow in green class', () => {
        const li = Watches.renderWatchRow(
            document,
            { ...baseBuy, side: 'SELL', threshold: 150.0 },
            120.0
        );
        const arrow = li.querySelector('.economy-watch-arrow');
        expect(arrow.textContent).toBe('↑');
        expect(arrow.classList.contains('economy-watch-arrow-up')).toBe(true);
    });

    test('formats threshold and priceAtCreation to 4dp', () => {
        const li = Watches.renderWatchRow(
            document,
            { ...baseBuy, threshold: 80.5, priceAtCreation: 100.123 },
            95.0
        );
        expect(li.querySelector('.economy-watch-target-value').textContent).toBe('80.5000');
        expect(li.querySelector('.economy-watch-from').textContent).toBe('from 100.1230');
    });

    test('status pill picks up status-armed when armed', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 95.0);
        const status = li.querySelector('.economy-watch-status');
        expect(status.classList.contains('status-armed')).toBe(true);
        expect(status.classList.contains('status-fire-now')).toBe(false);
    });

    test('status pill picks up status-fire-now when current price crossed', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 75.0);
        const status = li.querySelector('.economy-watch-status');
        expect(status.classList.contains('status-fire-now')).toBe(true);
        expect(status.textContent).toBe('would fire now');
    });

    test('inactive (fired) row gets is-inactive class so CSS can mute it', () => {
        const li = Watches.renderWatchRow(
            document,
            { ...baseBuy, enabled: false, firedAt: Date.now() },
            75.0
        );
        expect(li.classList.contains('is-inactive')).toBe(true);
        const status = li.querySelector('.economy-watch-status');
        expect(status.classList.contains('status-fired')).toBe(true);
    });

    test('remove button has aria-label and data-watch-id pointing at the row', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 95.0);
        const btn = li.querySelector('.economy-watch-remove');
        expect(btn).not.toBeNull();
        expect(btn.dataset.watchId).toBe('7');
        expect(btn.getAttribute('aria-label')).toBe('Remove watch #7');
        // Icon is an SVG so the button reads via aria-label, not glyph text.
        expect(btn.querySelector('svg')).not.toBeNull();
        // The text label exists for mobile via CSS — assert it's in the DOM.
        expect(btn.querySelector('.economy-watch-remove-text').textContent).toBe('Remove');
    });

    test('id tag renders the #N reference for the row', () => {
        const li = Watches.renderWatchRow(document, baseBuy, 95.0);
        expect(li.querySelector('.economy-watch-id').textContent).toBe('#7');
    });
});

describe('TobyWatches.renderWatches', () => {
    let listEl;
    let emptyEl;

    beforeEach(() => {
        document.body.innerHTML =
            '<ul id="list"></ul><p id="empty" hidden>No active price watches.</p>';
        listEl = document.getElementById('list');
        emptyEl = document.getElementById('empty');
    });

    test('shows empty state and hides list when watches is empty', () => {
        const n = Watches.renderWatches(listEl, emptyEl, [], 100.0);
        expect(n).toBe(0);
        expect(listEl.children.length).toBe(0);
        expect(emptyEl.hidden).toBe(false);
    });

    test('shows empty state when watches is null/undefined', () => {
        Watches.renderWatches(listEl, emptyEl, null, 100.0);
        expect(emptyEl.hidden).toBe(false);
        Watches.renderWatches(listEl, emptyEl, undefined, 100.0);
        expect(emptyEl.hidden).toBe(false);
    });

    test('appends one row per watch and hides empty state', () => {
        const watches = [
            {
                id: 1, side: 'BUY', amount: 5, threshold: 80.0,
                priceAtCreation: 100.0, enabled: true, firedAt: null,
            },
            {
                id: 2, side: 'SELL', amount: 3, threshold: 150.0,
                priceAtCreation: 100.0, enabled: true, firedAt: null,
            },
        ];
        const n = Watches.renderWatches(listEl, emptyEl, watches, 95.0);
        expect(n).toBe(2);
        expect(listEl.children.length).toBe(2);
        expect(emptyEl.hidden).toBe(true);
        expect(listEl.children[0].dataset.watchId).toBe('1');
        expect(listEl.children[1].dataset.watchId).toBe('2');
    });

    test('replaces previous content on re-render (no row pileup)', () => {
        Watches.renderWatches(listEl, emptyEl, [
            { id: 1, side: 'BUY', amount: 1, threshold: 80, priceAtCreation: 100, enabled: true, firedAt: null },
        ], 95.0);
        Watches.renderWatches(listEl, emptyEl, [
            { id: 2, side: 'SELL', amount: 1, threshold: 120, priceAtCreation: 100, enabled: true, firedAt: null },
        ], 110.0);
        expect(listEl.children.length).toBe(1);
        expect(listEl.children[0].dataset.watchId).toBe('2');
    });

    test('handles a missing emptyEl gracefully', () => {
        expect(() => Watches.renderWatches(listEl, null, [], 100.0)).not.toThrow();
        expect(() => Watches.renderWatches(listEl, null, [{
            id: 1, side: 'BUY', amount: 1, threshold: 80,
            priceAtCreation: 100, enabled: true, firedAt: null,
        }], 95.0)).not.toThrow();
        expect(listEl.children.length).toBe(1);
    });
});

// ---------------------------------------------------------------------------
// DOM-wiring (IIFE): the remove button's icon is an inline <svg>. Clicking it
// must still delete the watch. Regression: the delegated click handler used
// `instanceof HTMLElement`, which is false for SVG nodes, so clicks landing on
// the icon (most of the button) were silently swallowed.
// ---------------------------------------------------------------------------
describe('economy-watches.js remove-button wiring', () => {
    const MODULE = '../../main/resources/static/js/economy-watches';
    const WATCH = {
        id: 7, side: 'BUY', amount: 5, threshold: 80,
        priceAtCreation: 100, enabled: true, firedAt: null, createdAt: 0,
    };
    let delMock;

    beforeEach(() => {
        jest.resetModules();
        document.body.innerHTML = `
            <main data-guild-id="1" data-coin="TOBY"></main>
            <form id="economy-watch-form">
                <input id="economy-watch-price">
                <select id="economy-watch-side"><option value="BUY">BUY</option></select>
                <input id="economy-watch-amount">
                <button id="economy-watch-submit" type="submit"></button>
            </form>
            <ul id="economy-watch-list"></ul>
            <p id="economy-watch-empty" hidden></p>
        `;
        delMock = jest.fn(() => Promise.resolve({ ok: true }));
        window.TobyApi = { del: delMock, postJson: jest.fn(() => Promise.resolve({ ok: true })) };
        window.toast = jest.fn();
        // The IIFE calls loadAndRender() on boot; return an empty list so it
        // doesn't fight the row we paint explicitly below.
        global.fetch = jest.fn(() => Promise.resolve({
            json: () => Promise.resolve({ ok: true, price: 100, watches: [] }),
        }));
    });

    afterEach(() => {
        delete window.TobyApi;
        delete window.toast;
        delete window.TobyWatches;
        delete global.fetch;
    });

    // Boot the module (binds the delegated listener via the IIFE) and paint
    // one watch row with the same renderWatches the page uses — so the
    // inline-SVG remove button is present and wired to the live listener.
    async function bootWithOneRow() {
        require(MODULE);
        // Cross a macrotask boundary so the boot-time loadAndRender
        // (fetch -> render empty) fully settles before we paint our row;
        // otherwise its async empty render runs as a later microtask and
        // wipes the row out from under the click.
        await new Promise(function (resolve) { setTimeout(resolve, 0); });
        window.TobyWatches.renderWatches(
            document.getElementById('economy-watch-list'),
            document.getElementById('economy-watch-empty'),
            [WATCH],
            100,
        );
    }

    test('clicking the SVG icon inside the remove button deletes the watch', async () => {
        await bootWithOneRow();
        const iconPath = document.querySelector('.economy-watch-remove svg path');
        expect(iconPath).not.toBeNull(); // the icon is SVG — the regression surface
        iconPath.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        expect(delMock).toHaveBeenCalledWith('/economy/1/watches/7');
    });

    test('clicking the remove button text also deletes the watch', async () => {
        await bootWithOneRow();
        const text = document.querySelector('.economy-watch-remove-text');
        text.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        expect(delMock).toHaveBeenCalledWith('/economy/1/watches/7');
    });
});
