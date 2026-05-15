const {
    isJackpotHit,
    jackpotPrefixHtml,
    tierLabel,
    renderWinHtml,
    spinWheelFor,
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

describe('tierLabel', () => {
    test('falls back to a generic JACKPOT label when no pct supplied', () => {
        expect(tierLabel(undefined)).toBe('🎰 JACKPOT!');
        expect(tierLabel(0)).toBe('🎰 JACKPOT!');
    });
    test('returns the pity label for sub-2% tiers', () => {
        expect(tierLabel(1)).toContain('Pity prize');
    });
    test('returns the nice label for 2-9% tiers', () => {
        expect(tierLabel(2)).toContain('Nice payout');
        expect(tierLabel(5)).toContain('Nice payout');
        expect(tierLabel(9)).toContain('Nice payout');
    });
    test('returns the big-win label for 10-29% tiers', () => {
        expect(tierLabel(10)).toContain('BIG WIN');
        expect(tierLabel(20)).toContain('BIG WIN');
    });
    test('returns the mega label for 30%+ tiers', () => {
        expect(tierLabel(30)).toContain('MEGA JACKPOT');
        expect(tierLabel(50)).toContain('MEGA JACKPOT');
    });
});

describe('jackpotPrefixHtml', () => {
    test('renders a tiered JACKPOT banner with the payout amount and trailing <br>', () => {
        const html = jackpotPrefixHtml(1234, 50);
        expect(html).toContain('🎰');
        expect(html).toContain('MEGA JACKPOT');
        expect(html).toContain('+1234 credits');
        expect(html).toContain('<br>');
    });

    test('honours pity vs big tier', () => {
        expect(jackpotPrefixHtml(50, 1)).toContain('Pity prize');
        expect(jackpotPrefixHtml(100, 10)).toContain('BIG WIN');
    });

    test('returns empty string when payout is 0', () => {
        expect(jackpotPrefixHtml(0, 50)).toBe('');
    });

    test('returns empty string when payout is undefined', () => {
        expect(jackpotPrefixHtml(undefined, 50)).toBe('');
    });

    test('returns empty string when payout is negative', () => {
        expect(jackpotPrefixHtml(-50, 50)).toBe('');
    });
});

describe('spinWheelFor', () => {
    let wheelCalls;

    beforeEach(() => {
        wheelCalls = [];
        window.TobyJackpotWheel = {
            spinTo: (idx, amount, pct, cb) => {
                wheelCalls.push({ idx: idx, amount: amount, pct: pct });
                if (typeof cb === 'function') cb();
            },
        };
    });

    afterEach(() => { delete window.TobyJackpotWheel; });

    test('delegates to TobyJackpotWheel.spinTo with the picked tier and amount', () => {
        spinWheelFor({ jackpotPayout: 500, jackpotTierIndex: 2, jackpotTierPayoutPct: 0.10 });
        expect(wheelCalls.length).toBe(1);
        expect(wheelCalls[0]).toEqual({ idx: 2, amount: 500, pct: 10 });
    });

    test('is a no-op when no jackpot was hit', () => {
        spinWheelFor({ jackpotPayout: 0, jackpotTierIndex: 0, jackpotTierPayoutPct: 0.01 });
        expect(wheelCalls.length).toBe(0);
    });

    test('is a no-op when tier index is missing', () => {
        spinWheelFor({ jackpotPayout: 500 });
        expect(wheelCalls.length).toBe(0);
    });

    test('calls onSettle when the wheel module is absent', () => {
        delete window.TobyJackpotWheel;
        const settled = jest.fn();
        spinWheelFor({ jackpotPayout: 500, jackpotTierIndex: 0, jackpotTierPayoutPct: 0.01 }, settled);
        expect(settled).toHaveBeenCalled();
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
            { jackpotPayout: 500, jackpotTierPayoutPct: 0.50 },
            'slots-result-jackpot',
            winLine,
        );

        expect(html.startsWith('🎰')).toBe(true);
        expect(html).toContain('MEGA JACKPOT');
        expect(html).toContain('+500 credits');
        expect(html.endsWith(winLine)).toBe(true);
        expect(resultEl.classList.contains('slots-result-jackpot')).toBe(true);
    });

    test('renders the matching tier label for a low-pct hit', () => {
        const html = renderWinHtml(
            resultEl,
            { jackpotPayout: 12, jackpotTierPayoutPct: 0.01 },
            'slots-result-jackpot',
            winLine,
        );
        expect(html).toContain('Pity prize');
        expect(html).toContain('+12 credits');
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
