// Pure-math regression tests for the market-page trade preview. The
// formulas mirror EconomyTradeService.buy/sell and TobyCoinEngine, so
// drift between the two would be a silent UX bug — the preview would
// understate cost or overstate proceeds. Pin the math here.

const Quote = require('../../main/resources/static/js/economy-quote');

const STATE = Object.freeze({
    price: 100.0,
    coins: 50,
    credits: 10_000,
    buyFeeRate: 0.01,    // 1 %
    sellFeeRate: 0.01,
    tradeImpact: 0.0001, // matches TobyCoinEngine.TRADE_IMPACT
});

describe('quoteBuy', () => {
    test('returns zeros for non-positive N', () => {
        expect(Quote.quoteBuy(STATE, 0).cost).toBe(0);
        expect(Quote.quoteBuy(STATE, -5).cost).toBe(0);
    });

    test('returns zeros when price is zero', () => {
        expect(Quote.quoteBuy({ ...STATE, price: 0 }, 10).cost).toBe(0);
    });

    test('cost = ceil(midpoint * N) + ceil(gross * fee)', () => {
        // Reference: P=100, N=10, impact=0.0001 → newPrice = 100*1.001=100.1.
        // midpoint = 100.05. gross = ceil(1000.5) = 1001. fee = ceil(10.01)=11.
        // cost = 1012.
        const r = Quote.quoteBuy(STATE, 10);
        expect(r.gross).toBe(1001);
        expect(r.fee).toBe(11);
        expect(r.cost).toBe(1012);
    });

    test('cost grows monotonically with N (binary-search invariant)', () => {
        let prev = 0;
        for (let n = 1; n <= 50; n++) {
            const c = Quote.quoteBuy(STATE, n).cost;
            expect(c).toBeGreaterThan(prev);
            prev = c;
        }
    });

    test('zero fee rate produces a fee-free quote', () => {
        const r = Quote.quoteBuy({ ...STATE, buyFeeRate: 0 }, 10);
        expect(r.fee).toBe(0);
        expect(r.cost).toBe(r.gross);
    });
});

describe('quoteSell', () => {
    test('proceeds = floor(midpoint * N) - floor(gross * fee)', () => {
        // Reference: P=100, N=10, impact=0.0001 → newPrice = 100*0.999=99.9.
        // midpoint = 99.95. gross = floor(999.5) = 999. fee = floor(9.99) = 9.
        // proceeds = 990.
        const r = Quote.quoteSell(STATE, 10);
        expect(r.gross).toBe(999);
        expect(r.fee).toBe(9);
        expect(r.proceeds).toBe(990);
    });

    test('returns zeros for non-positive N', () => {
        expect(Quote.quoteSell(STATE, 0).proceeds).toBe(0);
        expect(Quote.quoteSell(STATE, -1).proceeds).toBe(0);
    });

    test('higher fee rate eats more of the proceeds', () => {
        const baseline = Quote.quoteSell(STATE, 100);
        const taxed = Quote.quoteSell({ ...STATE, sellFeeRate: 0.05 }, 100);
        expect(taxed.proceeds).toBeLessThan(baseline.proceeds);
    });
});

describe('maxAffordableBuy', () => {
    test('returns zero when the user has no credits', () => {
        expect(Quote.maxAffordableBuy({ ...STATE, credits: 0 })).toBe(0);
    });

    test('chosen N actually fits the budget', () => {
        const n = Quote.maxAffordableBuy(STATE);
        expect(Quote.quoteBuy(STATE, n).cost).toBeLessThanOrEqual(STATE.credits);
    });

    test('N+1 would exceed the budget (i.e. we picked the largest)', () => {
        const n = Quote.maxAffordableBuy(STATE);
        expect(Quote.quoteBuy(STATE, n + 1).cost).toBeGreaterThan(STATE.credits);
    });

    test('a higher fee rate strictly lowers the affordable N', () => {
        const lowFee = Quote.maxAffordableBuy({ ...STATE, buyFeeRate: 0.005 });
        const highFee = Quote.maxAffordableBuy({ ...STATE, buyFeeRate: 0.05 });
        expect(highFee).toBeLessThan(lowFee);
    });
});

describe('pctLabel', () => {
    test('strips trailing zeros from a whole-percent rate', () => {
        expect(Quote.pctLabel(0.01)).toBe('1%');
    });

    test('keeps significant decimals for sub-1% rates', () => {
        expect(Quote.pctLabel(0.005)).toBe('0.5%');
        expect(Quote.pctLabel(0.0025)).toBe('0.25%');
    });

    test('renders zero as 0% rather than 0.00%', () => {
        expect(Quote.pctLabel(0)).toBe('0%');
    });
});
