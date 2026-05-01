// Pure trade-math helpers for the market page. Mirrors the buy/sell
// arithmetic in EconomyTradeService so the page can show a live cost
// preview and a Max-buy affordability check without a server round-trip.
// The server still has the final say — this is purely UX scaffolding.

(function (root) {
    'use strict';

    function quoteBuy(state, n) {
        if (!n || n <= 0 || !state || state.price <= 0) {
            return { gross: 0, fee: 0, cost: 0, executionPrice: state ? state.price : 0, newPrice: state ? state.price : 0 };
        }
        const newPrice = Math.max(1.0, state.price * (1.0 + state.tradeImpact * n));
        const exec = (state.price + newPrice) / 2.0;
        const gross = Math.ceil(exec * n);
        const fee = Math.ceil(gross * state.buyFeeRate);
        return { gross: gross, fee: fee, cost: gross + fee, executionPrice: exec, newPrice: newPrice };
    }

    function quoteSell(state, n) {
        if (!n || n <= 0 || !state || state.price <= 0) {
            return { gross: 0, fee: 0, proceeds: 0, executionPrice: state ? state.price : 0, newPrice: state ? state.price : 0 };
        }
        const newPrice = Math.max(1.0, state.price * (1.0 - state.tradeImpact * n));
        const exec = (state.price + newPrice) / 2.0;
        const gross = Math.floor(exec * n);
        const fee = Math.floor(gross * state.sellFeeRate);
        return { gross: gross, fee: fee, proceeds: gross - fee, executionPrice: exec, newPrice: newPrice };
    }

    // Largest N for which quoteBuy(N).cost ≤ credits. cost(N) is monotonic
    // in N, so an upper-bound + binary search is exact and dodges
    // ceil/floor inversion math.
    function maxAffordableBuy(state) {
        if (!state || state.credits <= 0 || state.price <= 0) return 0;
        let hi = Math.max(1, Math.floor(state.credits / state.price));
        // Slippage + fee can push cost beyond the naive ceiling — push hi
        // up until it actually exceeds the budget before the binary search.
        // Cap the doubling loop so a degenerate state can't spin forever.
        for (let i = 0; i < 32 && quoteBuy(state, hi).cost <= state.credits; i++) hi *= 2;
        let lo = 0;
        while (lo < hi) {
            const mid = Math.floor((lo + hi + 1) / 2);
            if (quoteBuy(state, mid).cost <= state.credits) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    // Trim trailing zeros from a fixed-2 percent so "1.00%" reads as "1%"
    // but "0.50%" stays "0.5%". Matches the moderation page's input step.
    function pctLabel(rate) {
        return (rate * 100).toFixed(2).replace(/\.?0+$/, '') + '%';
    }

    const api = {
        quoteBuy: quoteBuy,
        quoteSell: quoteSell,
        maxAffordableBuy: maxAffordableBuy,
        pctLabel: pctLabel,
    };

    if (root) root.TobyEconomyQuote = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
