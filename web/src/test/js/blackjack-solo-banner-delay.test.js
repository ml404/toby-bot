// The win/lose banner on /blackjack/<guildId>/solo used to paint in
// the same tick that renderState() dealt the dealer's last card,
// spoiling the staggered reveal — by the time the user saw the last
// card slide in, "You win." had already replaced "Dealing…". Fix:
// renderState now defers the banner via createDeferredScheduler so
// the banner lands exactly as the last fresh card finishes its CSS
// animation. This test exercises the scheduler factory directly with
// fake timers so a regression in the timing logic is caught without
// having to boot the full poll-driven page.

require('../../main/resources/static/js/casino-render');
require('../../main/resources/static/js/blackjack-solo');

describe('TobyBlackjackSolo.createDeferredScheduler', () => {
    beforeEach(() => {
        jest.useFakeTimers();
    });

    afterEach(() => {
        jest.useRealTimers();
    });

    test('fires the callback synchronously when delayMs is 0 (no fresh cards on this poll)', () => {
        const scheduler = window.TobyBlackjackSolo.createDeferredScheduler();
        const cb = jest.fn();
        scheduler.schedule(0, cb);
        expect(cb).toHaveBeenCalledTimes(1);
    });

    test('holds the callback until delayMs has elapsed', () => {
        const scheduler = window.TobyBlackjackSolo.createDeferredScheduler();
        const cb = jest.fn();
        // 3 fresh dealer cards at 400ms stagger + 320ms reveal = 1120ms.
        scheduler.schedule(1120, cb);
        expect(cb).not.toHaveBeenCalled();
        jest.advanceTimersByTime(1119);
        expect(cb).not.toHaveBeenCalled();
        jest.advanceTimersByTime(1);
        expect(cb).toHaveBeenCalledTimes(1);
    });

    test('a fresh schedule cancels a pending one (rapid re-deal does not fire stale banner)', () => {
        const scheduler = window.TobyBlackjackSolo.createDeferredScheduler();
        const stale = jest.fn();
        const fresh = jest.fn();
        scheduler.schedule(1000, stale);
        // User cancels by dealing a new hand mid-defer; the new hand's
        // own schedule (or its cancel) MUST stop the previous timer.
        scheduler.schedule(500, fresh);
        jest.advanceTimersByTime(2000);
        expect(stale).not.toHaveBeenCalled();
        expect(fresh).toHaveBeenCalledTimes(1);
    });

    test('cancel() drops a pending callback (used when the player re-deals before the banner lands)', () => {
        const scheduler = window.TobyBlackjackSolo.createDeferredScheduler();
        const cb = jest.fn();
        scheduler.schedule(1000, cb);
        expect(scheduler.isPending()).toBe(true);
        scheduler.cancel();
        expect(scheduler.isPending()).toBe(false);
        jest.advanceTimersByTime(2000);
        expect(cb).not.toHaveBeenCalled();
    });

    test('the banner-defer duration matches what CasinoRender.renderCards reports', () => {
        // Integration check: the dealer's final play-out paints 3 fresh
        // cards at the shared 400ms stagger. renderCards reports
        // settleMs covering the last card's animation; the scheduler
        // uses that to defer the banner.
        document.body.innerHTML = '<div id="dealer"></div>';
        const dealerEl = document.getElementById('dealer');
        const ret = window.CasinoRender.renderCards(
            dealerEl,
            ['A♠', 'K♥', 'Q♣'],
            { staggerMs: window.CasinoRender.DEALER_REVEAL_STAGGER_MS }
        );
        expect(ret.settleMs).toBe(1120);

        const scheduler = window.TobyBlackjackSolo.createDeferredScheduler();
        const renderBanner = jest.fn();
        scheduler.schedule(ret.settleMs, renderBanner);

        // Pinning the exact duration here so a tweak to either the
        // stagger or the reveal animation length forces a deliberate
        // test update.
        jest.advanceTimersByTime(1119);
        expect(renderBanner).not.toHaveBeenCalled();
        jest.advanceTimersByTime(1);
        expect(renderBanner).toHaveBeenCalledTimes(1);
    });
});

describe('TobyBlackjackSolo.validateStake', () => {
    test('rejects non-positive / NaN with the shared "Enter a positive stake." message', () => {
        const v = window.TobyBlackjackSolo.validateStake;
        expect(v('0')).toBe('Enter a positive stake.');
        expect(v('-5')).toBe('Enter a positive stake.');
        expect(v('')).toBe('Enter a positive stake.');
        expect(v('abc')).toBe('Enter a positive stake.');
    });

    test('returns null on a positive integer stake', () => {
        const v = window.TobyBlackjackSolo.validateStake;
        expect(v('1')).toBeNull();
        expect(v('100')).toBeNull();
    });
});
