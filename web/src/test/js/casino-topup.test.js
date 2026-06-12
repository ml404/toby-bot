// Smoke-test the shared "Bet (sell TOBY)" helper. The math has to
// match TobyCoinEngine's proceeds-for-sell so the button promises
// the same coin count the server then actually sells.

const TobyTopUp = require('../../main/resources/static/js/casino-topup');

// ---------------------------------------------------------------------------
// proceedsForSell — must match Kotlin's TobyCoinEngine.proceedsForSell
// ---------------------------------------------------------------------------

describe('proceedsForSell', () => {
    test('returns 0 for non-positive coins', () => {
        expect(TobyTopUp.proceedsForSell(2.5, 0)).toBe(0);
        expect(TobyTopUp.proceedsForSell(2.5, -10)).toBe(0);
    });

    test('returns 0 for zero price', () => {
        expect(TobyTopUp.proceedsForSell(0, 100)).toBe(0);
    });

    test('matches the Kotlin engine reference (P=2.5, N=205)', () => {
        // midpoint = (2.5 + 2.5*0.9795)/2 = 2.474375.
        // gross = floor(2.474375 * 205) = 507. fee = floor(5.07) = 5.
        // net = 502.
        expect(TobyTopUp.proceedsForSell(2.5, 205)).toBe(502);
    });

    test('matches the Kotlin engine reference (P=10, N=31)', () => {
        // midpoint = (10 + 10*0.9969)/2 = 9.9845.
        // gross = floor(309.5195) = 309. fee = floor(3.09) = 3. net = 306.
        expect(TobyTopUp.proceedsForSell(10, 31)).toBe(306);
    });
});

// ---------------------------------------------------------------------------
// coinsNeededForShortfall
// ---------------------------------------------------------------------------

describe('coinsNeededForShortfall', () => {
    test('returns 0 for zero or negative shortfall', () => {
        expect(TobyTopUp.coinsNeededForShortfall(0, 10, 1000)).toBe(0);
        expect(TobyTopUp.coinsNeededForShortfall(-50, 10, 1000)).toBe(0);
    });

    test('returns 0 for non-positive price', () => {
        expect(TobyTopUp.coinsNeededForShortfall(100, 0, 1000)).toBe(0);
    });

    test('bumps for fee + slippage to match the backend (shortfall=500, P=2.5)', () => {
        expect(TobyTopUp.coinsNeededForShortfall(500, 2.5, 1000)).toBe(205);
    });

    test('bumps for fee + slippage (shortfall=300, P=10)', () => {
        expect(TobyTopUp.coinsNeededForShortfall(300, 10, 1000)).toBe(31);
    });

    test('signals "not enough coins" by returning maxCoins+1', () => {
        // user has 5, shortfall actually needs ~205 — helper short-
        // circuits early instead of looping through 200 wasted iterations.
        expect(TobyTopUp.coinsNeededForShortfall(500, 2.5, 5)).toBe(6);
    });
});

// ---------------------------------------------------------------------------
// soldPrefixHtml
// ---------------------------------------------------------------------------

describe('soldPrefixHtml', () => {
    test('renders the sold-coins line plus price when both supplied', () => {
        const html = TobyTopUp.soldPrefixHtml(12, 2.5);
        expect(html).toContain('Sold 12 TOBY');
        expect(html).toContain('@ 2.50');
        expect(html).toContain('<br>');
    });

    test('omits the price when newPrice is missing', () => {
        const html = TobyTopUp.soldPrefixHtml(5);
        expect(html).toContain('Sold 5 TOBY');
        expect(html).not.toContain('@');
    });

    test('returns empty string when soldTobyCoins is 0 or absent', () => {
        expect(TobyTopUp.soldPrefixHtml(0, 2.5)).toBe('');
        expect(TobyTopUp.soldPrefixHtml(undefined)).toBe('');
        expect(TobyTopUp.soldPrefixHtml(-1)).toBe('');
    });
});

// ---------------------------------------------------------------------------
// init — wires the secondary "Bet (sell TOBY)" button
// ---------------------------------------------------------------------------

describe('init', () => {
    function setupDom(opts) {
        document.body.innerHTML = `
            <form id="form">
                <input id="stake" type="number" value="${opts.stake || ''}">
                <button id="primary" type="submit"></button>
                <button id="toby" type="submit" hidden>
                    <span class="casino-bet-toby-coins">0</span>
                </button>
            </form>
            <span id="balance">${opts.balance || 0}</span>
        `;
        return TobyTopUp.init({
            form: document.getElementById('form'),
            stakeInput: document.getElementById('stake'),
            primaryBtn: document.getElementById('primary'),
            tobyBtn: document.getElementById('toby'),
            balanceEl: document.getElementById('balance'),
            tobyCoins: opts.tobyCoins || 0,
            marketPrice: opts.marketPrice || 0,
            onSubmit: opts.onSubmit || jest.fn(),
        });
    }

    test('hides the second button when balance covers the stake', () => {
        setupDom({ stake: 100, balance: 200, tobyCoins: 1000, marketPrice: 2.5 });
        expect(document.getElementById('toby').hidden).toBe(true);
    });

    test('shows the second button with the right coin count when balance is short', () => {
        setupDom({ stake: 500, balance: 0, tobyCoins: 1000, marketPrice: 2.5 });
        const btn = document.getElementById('toby');
        expect(btn.hidden).toBe(false);
        expect(btn.querySelector('.casino-bet-toby-coins').textContent).toBe('205');
    });

    test('hides the button when the market price is zero', () => {
        setupDom({ stake: 500, balance: 0, tobyCoins: 1000, marketPrice: 0 });
        expect(document.getElementById('toby').hidden).toBe(true);
    });

    test('hides the button when the player does not hold enough TOBY', () => {
        setupDom({ stake: 500, balance: 0, tobyCoins: 5, marketPrice: 2.5 });
        expect(document.getElementById('toby').hidden).toBe(true);
    });

    test('clicking the toby button triggers onSubmit with autoTopUp=true', () => {
        const onSubmit = jest.fn();
        setupDom({ stake: 500, balance: 0, tobyCoins: 1000, marketPrice: 2.5, onSubmit: onSubmit });
        document.getElementById('toby').click();
        document.getElementById('form').dispatchEvent(new Event('submit'));
        expect(onSubmit).toHaveBeenCalledWith(true);
    });

    test('clicking the primary button triggers onSubmit with autoTopUp=false', () => {
        const onSubmit = jest.fn();
        setupDom({ stake: 100, balance: 1000, tobyCoins: 1000, marketPrice: 2.5, onSubmit: onSubmit });
        document.getElementById('primary').click();
        document.getElementById('form').dispatchEvent(new Event('submit'));
        expect(onSubmit).toHaveBeenCalledWith(false);
    });

    test('setTobyCoins refreshes the button state with new wallet info', () => {
        const handle = setupDom({ stake: 500, balance: 0, tobyCoins: 1000, marketPrice: 2.5 });
        expect(document.getElementById('toby').hidden).toBe(false);

        handle.setTobyCoins(5);  // simulate wallet drained by a prior trade
        expect(document.getElementById('toby').hidden).toBe(true);
    });

    test('setMarketPrice refreshes the button state when the price moves', () => {
        const handle = setupDom({ stake: 500, balance: 0, tobyCoins: 1000, marketPrice: 2.5 });
        expect(document.getElementById('toby').hidden).toBe(false);

        handle.setMarketPrice(0);  // market crashed mid-session
        expect(document.getElementById('toby').hidden).toBe(true);
    });

    test('returns null when essential elements are missing', () => {
        const handle = TobyTopUp.init({
            form: null, stakeInput: null, primaryBtn: null, tobyBtn: null,
        });
        expect(handle).toBeNull();
    });
});

// ---------------------------------------------------------------------------
// casino-game.js wiring — applyBalance / applyTobyDelta must trigger a
// TobyTopUp.refresh so the "Bet (sell TOBY)" button hides itself when a
// win pushes the player's balance past their stake. Without this nudge
// the secondary button is stranded on screen because refresh()'s normal
// triggers (stake-input change, setTobyCoins, setMarketPrice) don't
// cover the post-win balance bump for a regular wager.
// ---------------------------------------------------------------------------

describe('casino-game → TobyTopUp refresh on balance updates', () => {
    let postJsonMock;

    beforeEach(() => {
        jest.resetModules();
        jest.useFakeTimers();
        document.body.innerHTML = `
            <form id="f">
                <input id="s" type="number" value="500">
                <button id="p" type="submit">Bet</button>
                <button id="t" type="submit" class="casino-bet-toby" hidden>
                    <span class="casino-bet-toby-coins">0</span>
                </button>
            </form>
            <span id="bal">0</span>
            <div id="r"></div>
        `;
        postJsonMock = jest.fn();
        window.TobyApi = { postJson: postJsonMock };
        require('../../main/resources/static/js/casino-balance');
        require('../../main/resources/static/js/casino-jackpot');
        require('../../main/resources/static/js/casino-topup');
        require('../../main/resources/static/js/casino-game');
    });

    afterEach(() => {
        jest.useRealTimers();
        delete window.TobyApi;
        delete window.TobyCasinoGame;
        delete window.TobyTopUp;
        delete window.TobyBalance;
        delete window.TobyJackpot;
    });

    function bootGame() {
        return window.TobyCasinoGame.init({
            guildId: 'g1',
            endpoint: '/play',
            form: document.getElementById('f'),
            stakeInput: document.getElementById('s'),
            primaryBtn: document.getElementById('p'),
            tobyBtn: document.getElementById('t'),
            balanceEl: document.getElementById('bal'),
            resultEl: document.getElementById('r'),
            tobyCoins: 1000,
            marketPrice: 2.5,
        });
    }

    test('applyBalance hides the TOBY button when the new balance covers the stake', () => {
        const game = bootGame();
        const tobyBtn = document.getElementById('t');
        // Pre-win: stake=500 > balance=0, market+coins both healthy → button visible.
        expect(tobyBtn.hidden).toBe(false);

        // A win lands and the wallet now covers the stake outright.
        game.applyBalance(600);

        expect(document.getElementById('bal').textContent).toBe('600');
        expect(tobyBtn.hidden).toBe(true);
    });

    test('applyTobyDelta with no soldTobyCoins still refreshes button visibility', () => {
        const game = bootGame();
        const tobyBtn = document.getElementById('t');
        expect(tobyBtn.hidden).toBe(false);

        // Simulate the balance moving via a different code path (e.g.
        // scratch's renderScratchResult writes via TobyBalance.update
        // directly), then call applyTobyDelta with a body that has
        // neither soldTobyCoins nor newPrice — the helper still needs to
        // refresh so the post-reveal balance hides the button.
        document.getElementById('bal').textContent = '700';
        game.applyTobyDelta({ newBalance: 700 });

        expect(tobyBtn.hidden).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// Multi-coin top-up: when the player can't cover with TOBY but holds other
// coins (read from the #casino-coin-holdings island the convert panel ships),
// the button still appears with a generic label and the server's sellToCover
// liquidates across coins.
// ---------------------------------------------------------------------------
describe('init multi-coin top-up', () => {
    function setupDom(opts) {
        const holdings = opts.holdings ? JSON.stringify(opts.holdings) : '[]';
        document.body.innerHTML = `
            <form id="form">
                <input id="stake" type="number" value="${opts.stake || ''}">
                <button id="primary" type="submit"></button>
                <button id="toby" type="submit" hidden>Bet (sell <span class="casino-bet-toby-coins">0</span> TOBY)</button>
            </form>
            <span id="balance">${opts.balance || 0}</span>
            <script type="application/json" id="casino-coin-holdings">${holdings}</script>
        `;
        return TobyTopUp.init({
            form: document.getElementById('form'),
            stakeInput: document.getElementById('stake'),
            primaryBtn: document.getElementById('primary'),
            tobyBtn: document.getElementById('toby'),
            balanceEl: document.getElementById('balance'),
            tobyCoins: opts.tobyCoins || 0,
            marketPrice: opts.marketPrice || 0,
            onSubmit: opts.onSubmit || jest.fn(),
        });
    }

    test('shows a generic "sell coins" label when MOON covers what TOBY cannot', () => {
        setupDom({
            stake: 500, balance: 0, tobyCoins: 0, marketPrice: 0,
            holdings: [{ symbol: 'MOON', name: 'Moonpup', amount: 1000, price: 100, impact: 0.00025 }],
        });
        const btn = document.getElementById('toby');
        expect(btn.hidden).toBe(false);
        expect(btn.textContent.trim()).toBe('Bet (sell coins)');
    });

    test('keeps the exact "sell N TOBY" label when TOBY alone can cover', () => {
        setupDom({
            stake: 500, balance: 0, tobyCoins: 1000, marketPrice: 2.5,
            holdings: [{ symbol: 'MOON', name: 'Moonpup', amount: 1000, price: 100, impact: 0.00025 }],
        });
        const btn = document.getElementById('toby');
        expect(btn.hidden).toBe(false);
        expect(btn.querySelector('.casino-bet-toby-coins').textContent).toBe('205');
    });

    test('hides when neither TOBY nor the other coins can cover the shortfall', () => {
        setupDom({
            stake: 500, balance: 0, tobyCoins: 0, marketPrice: 0,
            holdings: [{ symbol: 'MOON', name: 'Moonpup', amount: 1, price: 1, impact: 0.00025 }],
        });
        expect(document.getElementById('toby').hidden).toBe(true);
    });

    test('ignores a missing/empty holdings island (TOBY-only behaviour)', () => {
        setupDom({ stake: 500, balance: 0, tobyCoins: 5, marketPrice: 2.5 });
        // tobyCoins=5 can't cover and there are no other coins → hidden.
        expect(document.getElementById('toby').hidden).toBe(true);
    });
});
