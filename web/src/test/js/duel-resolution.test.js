// Coverage for the duel accept-resolution animation. We exercise the
// overlay-building path (`playDuelResolution`), the figure DOM
// (`makeFigure`), and the expiry-label formatting (`formatExpiry`).
// Animation timing is shortcut by passing `autoDismissMs`/`fadeOutMs` so
// the timers fire fast under jest fake timers.

const duel = require('../../main/resources/static/js/duel');
const { playDuelResolution, makeFigure, formatExpiry } = duel;

function makeRow({
    initiatorName = 'Alice',
    initiatorAvatar = 'https://cdn/alice.png',
    initiatorId = '100',
    opponentName = 'Bob',
    opponentAvatar = 'https://cdn/bob.png',
    opponentId = '200',
} = {}) {
    const row = document.createElement('div');
    row.className = 'duel-pending-row';
    row.dataset.initiatorName = initiatorName;
    if (initiatorAvatar) row.dataset.initiatorAvatar = initiatorAvatar;
    row.dataset.initiatorDiscordId = initiatorId;
    row.dataset.opponentName = opponentName;
    if (opponentAvatar) row.dataset.opponentAvatar = opponentAvatar;
    row.dataset.opponentDiscordId = opponentId;
    return row;
}

const baseResp = {
    winnerDiscordId: '100',
    loserDiscordId: '200',
    stake: 50,
    pot: 100,
    lossTribute: 10,
};

describe('formatExpiry', () => {
    // Anchor a fixed "now" so the maths is stable; the function takes nowMs
    // as a parameter precisely so tests can pin it without mocking Date.
    const now = 10_000_000 * 1000;

    test('returns empty when ttlSeconds is missing', () => {
        expect(formatExpiry(now / 1000 - 60, 0, now)).toBe('');
    });

    test('returns empty when createdAtSeconds is missing', () => {
        expect(formatExpiry(0, 180, now)).toBe('');
    });

    test('renders seconds when under a minute', () => {
        // createdAt was 175s ago, ttl=180 → 5s left.
        expect(formatExpiry(now / 1000 - 175, 180, now)).toBe('expires in 5s');
    });

    test('renders minutes-only when remaining lands on an exact minute', () => {
        // 60s ago, ttl=180 → 120s = 2m left.
        expect(formatExpiry(now / 1000 - 60, 180, now)).toBe('expires in 2m');
    });

    test('renders minutes + seconds when both nonzero', () => {
        // 45s ago, ttl=180 → 135s = 2m 15s left.
        expect(formatExpiry(now / 1000 - 45, 180, now)).toBe('expires in 2m 15s');
    });

    test('returns the "expiring…" sentinel once the deadline passes', () => {
        // createdAt was 200s ago, ttl=180 → already expired.
        expect(formatExpiry(now / 1000 - 200, 180, now)).toBe('expiring…');
    });
});

describe('makeFigure', () => {
    test('renders an avatar img when avatarUrl is provided', () => {
        const fig = makeFigure('Alice', 'https://cdn/alice.png', 'left');
        expect(fig.classList.contains('duel-figure')).toBe(true);
        expect(fig.classList.contains('duel-figure--left')).toBe(true);
        const img = fig.querySelector('.duel-figure-avatar img');
        expect(img).not.toBeNull();
        expect(img.getAttribute('src')).toBe('https://cdn/alice.png');
        expect(fig.querySelector('.duel-figure-name').textContent).toBe('Alice');
    });

    test('falls back to a CSS-rendered initial when avatarUrl is null', () => {
        const fig = makeFigure('Alice', null, 'right');
        expect(fig.classList.contains('duel-figure--right')).toBe(true);
        const av = fig.querySelector('.duel-figure-avatar');
        expect(av.classList.contains('is-fallback')).toBe(true);
        // The initial is the uppercased first character — CSS reads it via
        // `content: attr(data-initial)`.
        expect(av.dataset.initial).toBe('A');
        expect(av.querySelector('img')).toBeNull();
    });

    test('uses "?" as the initial when the name is missing', () => {
        const fig = makeFigure('', null, 'left');
        const av = fig.querySelector('.duel-figure-avatar');
        expect(av.dataset.initial).toBe('?');
    });

    test('does not render the name as HTML (XSS-safe)', () => {
        // A nickname like "<img onerror=…>" would otherwise break out of
        // the cell. textContent vs. innerHTML is the guard.
        const fig = makeFigure('<script>x</script>', 'https://cdn/x.png', 'left');
        const name = fig.querySelector('.duel-figure-name');
        expect(name.textContent).toBe('<script>x</script>');
        expect(name.querySelector('script')).toBeNull();
    });
});

describe('playDuelResolution', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
        jest.useFakeTimers();
    });

    afterEach(() => {
        // Tear down any overlays the test left attached.
        document.body.innerHTML = '';
        jest.useRealTimers();
    });

    test('appends a duel-resolution-overlay to body with the result line', () => {
        const row = makeRow();
        playDuelResolution(row, baseResp);

        const overlay = document.querySelector('.duel-resolution-overlay');
        expect(overlay).not.toBeNull();
        expect(overlay.getAttribute('role')).toBe('dialog');
        expect(overlay.querySelector('.duel-result-line').textContent)
            .toBe('Winner: Alice took 100 credits (10 to jackpot)');
        expect(overlay.querySelector('.duel-credits-pill').textContent).toBe('+100 credits');
    });

    test('marks the initiator as winner when winnerDiscordId matches initiator', () => {
        const row = makeRow();
        playDuelResolution(row, { ...baseResp, winnerDiscordId: '100' });

        const left = document.querySelector('.duel-figure--left');
        const right = document.querySelector('.duel-figure--right');
        expect(left.classList.contains('is-winner')).toBe(true);
        expect(left.classList.contains('is-loser')).toBe(false);
        expect(right.classList.contains('is-loser')).toBe(true);
        expect(right.classList.contains('is-winner')).toBe(false);
        expect(document.querySelector('.duel-credits-pill.flies-left')).not.toBeNull();
    });

    test('marks the opponent as winner when winnerDiscordId matches opponent', () => {
        const row = makeRow();
        playDuelResolution(row, { ...baseResp, winnerDiscordId: '200' });

        const left = document.querySelector('.duel-figure--left');
        const right = document.querySelector('.duel-figure--right');
        expect(left.classList.contains('is-loser')).toBe(true);
        expect(right.classList.contains('is-winner')).toBe(true);
        expect(document.querySelector('.duel-credits-pill.flies-right')).not.toBeNull();
        expect(document.querySelector('.duel-result-line').textContent)
            .toContain('Winner: Bob');
    });

    test('adds .is-reduced when prefers-reduced-motion matches', () => {
        // Stub window.matchMedia for this case; jsdom returns matches:false
        // by default which would otherwise hide this branch.
        const fakeWin = { matchMedia: jest.fn().mockReturnValue({ matches: true }) };
        const row = makeRow();
        playDuelResolution(row, baseResp, { window: fakeWin });

        const overlay = document.querySelector('.duel-resolution-overlay');
        expect(overlay.classList.contains('is-reduced')).toBe(true);
        expect(fakeWin.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    });

    test('omits .is-reduced when prefers-reduced-motion does not match', () => {
        const fakeWin = { matchMedia: jest.fn().mockReturnValue({ matches: false }) };
        const row = makeRow();
        playDuelResolution(row, baseResp, { window: fakeWin });
        expect(document.querySelector('.duel-resolution-overlay').classList.contains('is-reduced'))
            .toBe(false);
    });

    test('renders fallback initials when an avatar URL is missing', () => {
        const row = makeRow({ initiatorAvatar: null });
        playDuelResolution(row, baseResp);
        const left = document.querySelector('.duel-figure--left .duel-figure-avatar');
        expect(left.classList.contains('is-fallback')).toBe(true);
        expect(left.dataset.initial).toBe('A');
    });

    test('click dismisses the overlay and fires onDismiss after fade-out', () => {
        const onDismiss = jest.fn();
        const row = makeRow();
        const handle = playDuelResolution(row, baseResp, {
            onDismiss,
            autoDismissMs: 10_000,
            fadeOutMs: 100,
        });

        expect(document.querySelector('.duel-resolution-overlay')).not.toBeNull();
        handle.overlay.dispatchEvent(new Event('click'));
        // The overlay marks itself .is-dismissing immediately, then removes
        // itself after fadeOutMs.
        expect(handle.overlay.classList.contains('is-dismissing')).toBe(true);
        expect(onDismiss).not.toHaveBeenCalled();

        jest.advanceTimersByTime(100);
        expect(onDismiss).toHaveBeenCalledTimes(1);
        expect(document.querySelector('.duel-resolution-overlay')).toBeNull();
    });

    test('Escape key dismisses the overlay', () => {
        const onDismiss = jest.fn();
        const row = makeRow();
        playDuelResolution(row, baseResp, {
            onDismiss,
            autoDismissMs: 10_000,
            fadeOutMs: 50,
        });

        const evt = new KeyboardEvent('keydown', { key: 'Escape' });
        document.dispatchEvent(evt);
        jest.advanceTimersByTime(50);
        expect(onDismiss).toHaveBeenCalledTimes(1);
        expect(document.querySelector('.duel-resolution-overlay')).toBeNull();
    });

    test('non-Escape keys are ignored', () => {
        const onDismiss = jest.fn();
        const row = makeRow();
        playDuelResolution(row, baseResp, {
            onDismiss,
            autoDismissMs: 10_000,
            fadeOutMs: 50,
        });

        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
        document.dispatchEvent(new KeyboardEvent('keydown', { key: ' ' }));
        jest.advanceTimersByTime(50);
        expect(onDismiss).not.toHaveBeenCalled();
        expect(document.querySelector('.duel-resolution-overlay')).not.toBeNull();
    });

    test('auto-dismisses after autoDismissMs without interaction', () => {
        const onDismiss = jest.fn();
        const row = makeRow();
        playDuelResolution(row, baseResp, {
            onDismiss,
            autoDismissMs: 200,
            fadeOutMs: 50,
        });

        jest.advanceTimersByTime(199);
        expect(onDismiss).not.toHaveBeenCalled();

        jest.advanceTimersByTime(1); // hit 200
        jest.advanceTimersByTime(50); // fade-out
        expect(onDismiss).toHaveBeenCalledTimes(1);
        expect(document.querySelector('.duel-resolution-overlay')).toBeNull();
    });

    test('dismiss is idempotent — double-click does not double-fire onDismiss', () => {
        const onDismiss = jest.fn();
        const row = makeRow();
        const handle = playDuelResolution(row, baseResp, {
            onDismiss,
            autoDismissMs: 10_000,
            fadeOutMs: 50,
        });

        handle.overlay.dispatchEvent(new Event('click'));
        handle.overlay.dispatchEvent(new Event('click'));
        // Auto-dismiss timer could also fire — make sure it doesn't add a
        // second invocation either.
        jest.advanceTimersByTime(10_000);
        jest.advanceTimersByTime(50);
        expect(onDismiss).toHaveBeenCalledTimes(1);
    });

    test('uses default names when the row dataset omits them', () => {
        const row = document.createElement('div');
        row.dataset.initiatorDiscordId = '100';
        row.dataset.opponentDiscordId = '200';
        playDuelResolution(row, baseResp);

        const left = document.querySelector('.duel-figure--left .duel-figure-name');
        const right = document.querySelector('.duel-figure--right .duel-figure-name');
        expect(left.textContent).toBe('Challenger');
        expect(right.textContent).toBe('You');
    });

    test('works with an offscreen synthetic row (the preview-button path)', () => {
        // The preview button builds an unattached <div> with the same eight
        // data attributes the live accept handler reads off the inbox row.
        // Verify the animation renders identically regardless of whether
        // the row is parented to anything.
        const row = document.createElement('div');
        row.dataset.initiatorName = 'Me';
        row.dataset.initiatorAvatar = 'https://cdn/me.png';
        row.dataset.initiatorDiscordId = '100';
        row.dataset.opponentName = 'Carol';
        row.dataset.opponentAvatar = 'https://cdn/carol.png';
        row.dataset.opponentDiscordId = '300';
        expect(row.parentNode).toBeNull(); // unattached on purpose

        playDuelResolution(row, {
            winnerDiscordId: '300',
            loserDiscordId: '100',
            stake: 25,
            pot: 50,
            lossTribute: 2,
        });

        const overlay = document.querySelector('.duel-resolution-overlay');
        expect(overlay).not.toBeNull();
        expect(overlay.querySelector('.duel-figure--right.is-winner')).not.toBeNull();
        expect(overlay.querySelector('.duel-figure--left.is-loser')).not.toBeNull();
        expect(overlay.querySelector('.duel-result-line').textContent)
            .toBe('Winner: Carol took 50 credits (2 to jackpot)');
        expect(overlay.querySelector('.duel-credits-pill.flies-right')).not.toBeNull();
    });
});
