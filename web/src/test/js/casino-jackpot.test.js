const {
    isJackpotHit,
    jackpotPrefixHtml,
    renderWinHtml,
    lossTributeSuffix,
    updatePoolBanner,
    holdPoolBanner,
    releasePoolBanner,
} = require('../../main/resources/static/js/casino-jackpot');

// ---------------------------------------------------------------------------
// isJackpotHit
// ---------------------------------------------------------------------------

describe('isJackpotHit', () => {
    test('truthy on positive payout', () => {
        expect(isJackpotHit({ jackpotPayout: 250 })).toBe(true);
    });

    test('false on zero payout', () => {
        expect(isJackpotHit({ jackpotPayout: 0 })).toBe(false);
    });

    test('false on missing payout', () => {
        expect(isJackpotHit({})).toBe(false);
    });

    test('false on null body', () => {
        expect(isJackpotHit(null)).toBe(false);
    });

    test('false on non-numeric payout', () => {
        expect(isJackpotHit({ jackpotPayout: '100' })).toBe(false);
    });
});

// ---------------------------------------------------------------------------
// jackpotPrefixHtml
// ---------------------------------------------------------------------------

describe('jackpotPrefixHtml', () => {
    test('renders the JACKPOT banner with payout amount and a trailing <br>', () => {
        const html = jackpotPrefixHtml(1234);
        expect(html).toContain('🎰');
        expect(html).toContain('<strong>JACKPOT!</strong>');
        expect(html).toContain('+1234 credits');
        expect(html).toContain('<br>');
    });

    test('returns empty string when payout is 0', () => {
        expect(jackpotPrefixHtml(0)).toBe('');
    });

    test('returns empty string when payout is undefined', () => {
        expect(jackpotPrefixHtml(undefined)).toBe('');
    });

    test('returns empty string when payout is negative', () => {
        expect(jackpotPrefixHtml(-50)).toBe('');
    });
});

// ---------------------------------------------------------------------------
// renderWinHtml
// ---------------------------------------------------------------------------

describe('renderWinHtml', () => {
    let resultEl;
    const winLine = '<strong>+100 credits</strong>';

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        resultEl = document.getElementById('r');
    });

    test('returns the win line as-is when no jackpot was hit', () => {
        const html = renderWinHtml(resultEl, { jackpotPayout: 0 }, 'slots-result-jackpot', winLine);

        expect(html).toBe(winLine);
        expect(resultEl.classList.contains('slots-result-jackpot')).toBe(false);
    });

    test('prepends the jackpot prefix and adds the jackpot class on a hit', () => {
        const html = renderWinHtml(
            resultEl,
            { jackpotPayout: 500 },
            'slots-result-jackpot',
            winLine,
        );

        expect(html.startsWith('🎰')).toBe(true);
        expect(html).toContain('+500 credits');
        expect(html.endsWith(winLine)).toBe(true);
        expect(resultEl.classList.contains('slots-result-jackpot')).toBe(true);
    });

    test('honours per-game class names', () => {
        renderWinHtml(resultEl, { jackpotPayout: 1 }, 'highlow-result-jackpot', winLine);

        expect(resultEl.classList.contains('highlow-result-jackpot')).toBe(true);
        expect(resultEl.classList.contains('slots-result-jackpot')).toBe(false);
    });

    test('does not touch the element when there is no hit and no class', () => {
        renderWinHtml(resultEl, null, 'slots-result-jackpot', winLine);

        expect(resultEl.classList.length).toBe(0);
    });
});

// ---------------------------------------------------------------------------
// lossTributeSuffix
// ---------------------------------------------------------------------------

describe('pool banner lock', () => {
    let strong;

    beforeEach(() => {
        document.body.innerHTML =
            '<div class="casino-jackpot-banner"><strong>0</strong></div>';
        strong = document.querySelector('.casino-jackpot-banner strong');
        // Force-release in case a prior test left the lock held — the
        // module-level state survives between tests.
        releasePoolBanner();
    });

    test('updatePoolBanner paints immediately when no lock is held', () => {
        updatePoolBanner({ jackpotPool: 100 });
        expect(strong.textContent).toBe('100');
    });

    test('updatePoolBanner is a no-op while the banner is held', () => {
        holdPoolBanner();
        updatePoolBanner({ jackpotPool: 250 });
        expect(strong.textContent).toBe('0');
        releasePoolBanner();
    });

    test('releasePoolBanner flushes the latest queued value', () => {
        holdPoolBanner();
        updatePoolBanner({ jackpotPool: 250 });
        expect(strong.textContent).toBe('0');
        releasePoolBanner();
        expect(strong.textContent).toBe('250');
    });

    test('last-write-wins under hold — only the final pool paints on release', () => {
        holdPoolBanner();
        updatePoolBanner({ jackpotPool: 100 });
        updatePoolBanner({ jackpotPool: 200 });
        updatePoolBanner({ jackpotPool: 300 });
        expect(strong.textContent).toBe('0');
        releasePoolBanner();
        expect(strong.textContent).toBe('300');
    });

    test('release with no queued body leaves the DOM unchanged', () => {
        strong.textContent = '42';
        holdPoolBanner();
        releasePoolBanner();
        expect(strong.textContent).toBe('42');
    });

    test('release without a prior hold is a no-op and does not throw', () => {
        strong.textContent = '7';
        expect(() => releasePoolBanner()).not.toThrow();
        expect(strong.textContent).toBe('7');
    });

    test('hold/update/release survives a missing banner element', () => {
        document.body.innerHTML = '';
        expect(() => {
            holdPoolBanner();
            updatePoolBanner({ jackpotPool: 999 });
            releasePoolBanner();
        }).not.toThrow();
    });

    test('updates after release paint immediately again', () => {
        holdPoolBanner();
        updatePoolBanner({ jackpotPool: 10 });
        releasePoolBanner();
        expect(strong.textContent).toBe('10');

        updatePoolBanner({ jackpotPool: 20 });
        expect(strong.textContent).toBe('20');
    });
});

describe('lossTributeSuffix', () => {
    test('renders a "+N to jackpot" span with the casino-loss-tribute class', () => {
        const html = lossTributeSuffix({ lossTribute: 10 });
        expect(html).toContain('+10 to jackpot');
        expect(html).toContain('casino-loss-tribute');
        expect(html.startsWith(' &middot; ')).toBe(true);
    });

    test('returns empty string when no tribute', () => {
        expect(lossTributeSuffix({ lossTribute: 0 })).toBe('');
        expect(lossTributeSuffix({})).toBe('');
        expect(lossTributeSuffix(null)).toBe('');
        expect(lossTributeSuffix({ lossTribute: -5 })).toBe('');
    });

    test('returns empty string for non-numeric tribute', () => {
        expect(lossTributeSuffix({ lossTribute: '10' })).toBe('');
    });
});
