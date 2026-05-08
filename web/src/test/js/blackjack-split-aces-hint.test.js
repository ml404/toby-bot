// Two related fixes covered here:
//
// 1. Pre-action hint: the rule that split aces auto-stand on one card and
//    the dealer plays right after is documented in the Rules list, but
//    buried — players reported the round "skipping ahead" right after
//    tapping Split on aces. The page now surfaces a contextual banner
//    when the active hand is a pair of aces and SPLIT is offered.
//
// 2. Per-hand result display: BlackjackService.settleSolo only records
//    hand 0's outcome in seatResults (firstHandResult), so a split round
//    where hand 1 pushed and hand 2 won used to render only "Push —
//    stake refunded.", silently dropping hand 2's win from the banner
//    even though the wallet was correctly paid. renderResult now reads
//    perHandResults and prints one line per hand whenever a seat played
//    more than one hand.

require('../../main/resources/static/js/casino-render');
require('../../main/resources/static/js/blackjack-solo');

const T = window.TobyBlackjackSolo;

describe('TobyBlackjackSolo.isPairOfAces', () => {
    test('two aces of any suits → true', () => {
        expect(T.isPairOfAces(['A♠', 'A♥'])).toBe(true);
        expect(T.isPairOfAces(['A♣', 'A♦'])).toBe(true);
    });
    test('non-aces or mixed → false', () => {
        expect(T.isPairOfAces(['8♠', '8♥'])).toBe(false);
        expect(T.isPairOfAces(['A♠', 'K♥'])).toBe(false);
        expect(T.isPairOfAces(['A♠'])).toBe(false);
        expect(T.isPairOfAces(['A♠', 'A♥', 'A♦'])).toBe(false);
        expect(T.isPairOfAces([])).toBe(false);
        expect(T.isPairOfAces(null)).toBe(false);
    });
});

describe('TobyBlackjackSolo.hasSplitAceResolution', () => {
    test('any fromSplit branch starting with an ace → true', () => {
        expect(T.hasSplitAceResolution([
            { fromSplit: true, cards: ['A♦', '8♣'], result: 'PUSH' },
            { fromSplit: true, cards: ['A♣', '9♣'], result: 'PLAYER_WIN' },
        ])).toBe(true);
    });
    test('non-ace splits → false', () => {
        expect(T.hasSplitAceResolution([
            { fromSplit: true, cards: ['8♠', '5♣'], result: 'PLAYER_WIN' },
            { fromSplit: true, cards: ['8♥', 'J♦'], result: 'PLAYER_WIN' },
        ])).toBe(false);
    });
    test('non-split aces (initial deal) → false', () => {
        expect(T.hasSplitAceResolution([
            { fromSplit: false, cards: ['A♠', 'K♥'], result: 'PLAYER_BLACKJACK' },
        ])).toBe(false);
    });
    test('empty / missing → false', () => {
        expect(T.hasSplitAceResolution([])).toBe(false);
        expect(T.hasSplitAceResolution(undefined)).toBe(false);
        expect(T.hasSplitAceResolution(null)).toBe(false);
    });
});

describe('TobyBlackjackSolo.labelForResult', () => {
    test('maps each enum to user-facing copy and CSS class', () => {
        expect(T.labelForResult('PLAYER_BLACKJACK')).toEqual({ label: 'Blackjack! Paid 3:2.', cls: 'bj-win' });
        expect(T.labelForResult('PLAYER_WIN')).toEqual({ label: 'You win.', cls: 'bj-win' });
        expect(T.labelForResult('PUSH')).toEqual({ label: 'Push — stake refunded.', cls: 'muted' });
        expect(T.labelForResult('DEALER_WIN')).toEqual({ label: 'Dealer wins.', cls: 'bj-lose' });
        expect(T.labelForResult('PLAYER_BUST')).toEqual({ label: 'Bust.', cls: 'bj-lose' });
    });
    test('unknown result → empty muted label', () => {
        expect(T.labelForResult('NOT_A_RESULT')).toEqual({ label: '', cls: 'muted' });
        expect(T.labelForResult(undefined)).toEqual({ label: '', cls: 'muted' });
    });
});

describe('TobyBlackjackSolo.dominantResultClass', () => {
    // Mirrors the wallet's actual direction: any win shifts the banner
    // green even if the other hand pushed or lost, because the player
    // ended the round ahead. Push-only stays neutral. All-loss is red.
    test('push + win → bj-win (the bug-report scenario)', () => {
        expect(T.dominantResultClass([
            { result: 'PUSH' }, { result: 'PLAYER_WIN' },
        ])).toBe('bj-win');
    });
    test('blackjack counts as a win', () => {
        expect(T.dominantResultClass([
            { result: 'PLAYER_BLACKJACK' }, { result: 'DEALER_WIN' },
        ])).toBe('bj-win');
    });
    test('all push → muted', () => {
        expect(T.dominantResultClass([
            { result: 'PUSH' }, { result: 'PUSH' },
        ])).toBe('muted');
    });
    test('push + loss → bj-lose', () => {
        expect(T.dominantResultClass([
            { result: 'PUSH' }, { result: 'DEALER_WIN' },
        ])).toBe('bj-lose');
    });
    test('all loss → bj-lose', () => {
        expect(T.dominantResultClass([
            { result: 'DEALER_WIN' }, { result: 'PLAYER_BUST' },
        ])).toBe('bj-lose');
    });
});
