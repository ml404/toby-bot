// Unit tests for CasinoRender.flashWinPayout — the shared helper that
// every casino game calls to drop a chip stack onto the felt on a win.
// flashChipsOn (the lower-level primitive) is exercised in passing.

require('../../main/resources/static/js/casino-render');

describe('CasinoRender.flashWinPayout', () => {
    let seatEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="seat"></div>';
        seatEl = document.getElementById('seat');
    });

    test('no-ops on body.win = false', () => {
        window.CasinoRender.flashWinPayout(seatEl, { win: false, net: 100 });
        expect(seatEl.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('no-ops on missing body', () => {
        window.CasinoRender.flashWinPayout(seatEl, null);
        expect(seatEl.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('no-ops on missing seat element', () => {
        expect(() => window.CasinoRender.flashWinPayout(null, { win: true, net: 100 }))
            .not.toThrow();
    });

    test('no-ops on win with non-positive net and no jackpot', () => {
        window.CasinoRender.flashWinPayout(seatEl, { win: true, net: 0 });
        expect(seatEl.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('drops a chip stack with the net payout when win is true', () => {
        window.CasinoRender.flashWinPayout(seatEl, { win: true, net: 100 });
        const stack = seatEl.querySelector('.casino-chip-stack');
        expect(stack).not.toBeNull();
        const label = stack.querySelector('.casino-chip-payout');
        expect(label).not.toBeNull();
        expect(label.textContent).toBe('+100');
    });

    test('prefers jackpotPayout over net when jackpotPayout > 0', () => {
        window.CasinoRender.flashWinPayout(seatEl, {
            win: true, net: 100, jackpotPayout: 999,
        });
        const label = seatEl.querySelector('.casino-chip-payout');
        expect(label.textContent).toBe('+999');
    });

    test('falls back to net when jackpotPayout is zero or missing', () => {
        window.CasinoRender.flashWinPayout(seatEl, {
            win: true, net: 250, jackpotPayout: 0,
        });
        const label = seatEl.querySelector('.casino-chip-payout');
        expect(label.textContent).toBe('+250');
    });

    test('replaces a prior chip stack instead of stacking duplicates', () => {
        // Two consecutive wins on the same seat (e.g. two hands in a row)
        // should not leave a ghost stack from the previous round.
        window.CasinoRender.flashWinPayout(seatEl, { win: true, net: 100 });
        window.CasinoRender.flashWinPayout(seatEl, { win: true, net: 200 });
        const stacks = seatEl.querySelectorAll('.casino-chip-stack');
        expect(stacks.length).toBe(1);
        expect(stacks[0].querySelector('.casino-chip-payout').textContent).toBe('+200');
    });
});

describe('CasinoRender.renderCards', () => {
    let containerEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="cards"></div>';
        containerEl = document.getElementById('cards');
    });

    test('flags freshly arrived cards with .is-dealt and a stagger delay', () => {
        window.CasinoRender.renderCards(containerEl, ['A♠', 'K♥']);
        const cards = containerEl.querySelectorAll('.casino-card-glyph');
        expect(cards.length).toBe(2);
        // First card animates immediately; second card carries a 90ms stagger.
        expect(cards[0].classList.contains('is-dealt')).toBe(true);
        expect(cards[0].style.animationDelay).toBe('0ms');
        expect(cards[1].classList.contains('is-dealt')).toBe(true);
        expect(cards[1].style.animationDelay).toBe('90ms');
    });

    test('does not re-animate cards that were already there last render', () => {
        window.CasinoRender.renderCards(containerEl, ['A♠', 'K♥']);
        // Same array on the next poll — no fresh cards.
        window.CasinoRender.renderCards(containerEl, ['A♠', 'K♥']);
        const cards = containerEl.querySelectorAll('.casino-card-glyph');
        expect(cards.length).toBe(2);
        expect(cards[0].classList.contains('is-dealt')).toBe(false);
        expect(cards[1].classList.contains('is-dealt')).toBe(false);
    });

    test('treats a hole-card unmask (?? → real card) as a fresh arrival', () => {
        // Initial dealer-view: upcard + masked hole card.
        window.CasinoRender.renderCards(containerEl, ['A♠', '??']);
        // Resolution flips the hole card to a real value.
        window.CasinoRender.renderCards(containerEl, ['A♠', 'K♥']);
        const cards = containerEl.querySelectorAll('.casino-card-glyph');
        // Index 0 unchanged → no animation. Index 1 is fresh (glyph
        // changed) → animates with .is-dealt + .is-revealed (subsequent
        // fresh card flips, not slides in).
        expect(cards[0].classList.contains('is-dealt')).toBe(false);
        expect(cards[1].classList.contains('is-dealt')).toBe(true);
    });

    test('honours an explicit staggerMs override', () => {
        // Dealer container uses a 400ms stagger so the play-out beats out.
        window.CasinoRender.renderCards(containerEl, ['A♠', 'K♥', 'Q♣'], { staggerMs: 400 });
        const cards = containerEl.querySelectorAll('.casino-card-glyph');
        expect(cards[0].style.animationDelay).toBe('0ms');
        expect(cards[1].style.animationDelay).toBe('400ms');
        expect(cards[2].style.animationDelay).toBe('800ms');
    });

    test('count decrease (fresh deal) re-animates every card', () => {
        window.CasinoRender.renderCards(containerEl, ['A♠', 'K♥', 'Q♣']);
        // Next hand: fewer cards → reset baseline.
        window.CasinoRender.renderCards(containerEl, ['T♦', 'J♥']);
        const cards = containerEl.querySelectorAll('.casino-card-glyph');
        expect(cards.length).toBe(2);
        expect(cards[0].classList.contains('is-dealt')).toBe(true);
        expect(cards[1].classList.contains('is-dealt')).toBe(true);
    });
});
