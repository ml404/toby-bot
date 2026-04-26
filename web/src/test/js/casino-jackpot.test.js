const {
    isJackpotHit,
    jackpotPrefixHtml,
    renderWinHtml,
    lossTributeSuffix,
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
