// Regression for the "after split it just keeps dealing the same cards
// into both hands repeatedly" bug. The old `renderSplitHands` wiped
// `container.innerHTML` on every poll and rebuilt every sub-hand DOM
// from scratch, so the inner `.bj-cards` container was a brand-new
// element each render. casino-render.js's `dealtCounts` WeakMap is
// keyed off that element — with a fresh element it always reset to 0,
// re-animating every card with the deal sound on each poll cycle.
//
// The fix reuses the per-slot DOM across renders so the WeakMap entry
// persists. This test loads `casino-render.js` and `blackjack-solo.js`
// into jsdom, calls `renderSplitHands` twice with identical state, and
// asserts:
//   1. The cards container DOM nodes are identity-stable across calls.
//   2. The cards inside aren't re-marked with `.is-dealt` (no animation).
require('../../main/resources/static/js/casino-render');
require('../../main/resources/static/js/blackjack-solo');

describe('TobyBlackjackSolo.renderSplitHands', () => {
    let container;
    const slots = [
        { cards: ['8♠', '5♣'], total: 13, stake: 100, doubled: false, status: 'ACTIVE', fromSplit: true },
        { cards: ['8♥', 'J♦'], total: 18, stake: 100, doubled: false, status: 'ACTIVE', fromSplit: true },
    ];

    beforeEach(() => {
        document.body.innerHTML = '<div id="hands"></div>';
        container = document.getElementById('hands');
    });

    test('reuses the same .bj-cards DOM nodes across identical renders', () => {
        window.TobyBlackjackSolo.renderSplitHands(container, slots, 0);
        const firstCardEls = Array.from(container.querySelectorAll('.bj-cards'));
        expect(firstCardEls).toHaveLength(2);

        window.TobyBlackjackSolo.renderSplitHands(container, slots, 0);
        const secondCardEls = Array.from(container.querySelectorAll('.bj-cards'));
        expect(secondCardEls).toHaveLength(2);

        // Identity check — same element references on the second render.
        // If renderSplitHands wipes innerHTML, these would be different
        // nodes and the deal animation would re-fire.
        expect(secondCardEls[0]).toBe(firstCardEls[0]);
        expect(secondCardEls[1]).toBe(firstCardEls[1]);
    });

    test('re-rendering identical state does not re-animate the cards', () => {
        window.TobyBlackjackSolo.renderSplitHands(container, slots, 0);
        // After the first render every card glyph carries `.is-dealt`
        // because they were freshly arrived. Strip the marker so we can
        // assert the second render doesn't re-add it.
        container.querySelectorAll('.casino-card-glyph').forEach((el) => {
            el.classList.remove('is-dealt');
        });

        window.TobyBlackjackSolo.renderSplitHands(container, slots, 0);
        const reanimated = container.querySelectorAll('.casino-card-glyph.is-dealt');
        expect(reanimated.length).toBe(0);
    });

    test('rebuilds when the slot count changes (split happens, or fresh deal collapses back)', () => {
        // Render with 2 slots, then with 1 — the row count differs, so the
        // implementation is allowed to (and should) wipe and rebuild.
        window.TobyBlackjackSolo.renderSplitHands(container, slots, 0);
        expect(container.querySelectorAll('.bj-cards')).toHaveLength(2);

        window.TobyBlackjackSolo.renderSplitHands(container, [slots[0]], 0);
        expect(container.querySelectorAll('.bj-cards')).toHaveLength(1);
    });

    test('updates the active-slot highlight without rebuilding the DOM', () => {
        window.TobyBlackjackSolo.renderSplitHands(container, slots, 0);
        const seats = container.querySelectorAll('.bj-seat');
        expect(seats[0].classList.contains('is-active')).toBe(true);
        expect(seats[1].classList.contains('is-active')).toBe(false);

        window.TobyBlackjackSolo.renderSplitHands(container, slots, 1);
        const seatsAfter = container.querySelectorAll('.bj-seat');
        // Same DOM nodes, just toggled classes.
        expect(seatsAfter[0]).toBe(seats[0]);
        expect(seatsAfter[1]).toBe(seats[1]);
        expect(seatsAfter[0].classList.contains('is-active')).toBe(false);
        expect(seatsAfter[1].classList.contains('is-active')).toBe(true);
    });
});
