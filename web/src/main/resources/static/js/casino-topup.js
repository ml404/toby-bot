// Shared "Bet (sell TOBY)" button wiring for the casino minigame
// pages. Each game's IIFE-style page JS calls TobyTopUp.init() once
// to bind the second submit button to the same shortfall-recompute
// logic, so /slots, /coinflip, /dice, /highlow, /scratch all behave
// identically when a player has TOBY but not enough credits to cover
// their stake.
//
// Mirrors `EconomyTradeService.sell` math (midpoint slippage + 1 %
// trade fee → jackpot pool) so the displayed "sell N TOBY" amount on
// the button matches the actual coins debited server-side.

(function (root) {
    'use strict';

    // Keep these in lockstep with TobyCoinEngine.kt — drift means the
    // button promises a sell size the server then rejects as too small.
    const TRADE_IMPACT = 0.0001;
    const TRADE_FEE = 0.01;

    function proceedsForSell(price, coins) {
        if (coins <= 0 || price <= 0) return 0;
        const newPrice = Math.max(1.0, price * (1.0 - TRADE_IMPACT * coins));
        const midpoint = (price + newPrice) / 2.0;
        const gross = Math.floor(midpoint * coins);
        const fee = Math.floor(gross * TRADE_FEE);
        return gross - fee;
    }

    function coinsNeededForShortfall(shortfall, price, maxCoins) {
        if (shortfall <= 0 || price <= 0) return 0;
        let n = Math.max(1, Math.ceil(shortfall / price));
        for (let i = 0; i < 16; i++) {
            if (n > maxCoins) return maxCoins + 1;
            if (proceedsForSell(price, n) >= shortfall) return n;
            n += 1;
        }
        return n;
    }

    /**
     * Wire the second "Bet (sell TOBY)" submit button. Recomputes the
     * required coin count whenever the stake input changes, hides the
     * button when the player has enough credits to cover the wager.
     *
     * Caller supplies `onSubmit(autoTopUp)` to actually trigger the
     * wager request — the helper is concerned only with shortfall
     * math + button state, not with networking.
     *
     * Options:
     *   form           — the bet form
     *   stakeInput     — number input
     *   primaryBtn     — main submit button
     *   tobyBtn        — secondary submit button (the one this helper drives)
     *   balanceEl      — element whose textContent is the live credit balance
     *   tobyCoins      — initial TOBY count (refreshed by setTobyCoins)
     *   marketPrice    — pre-pressure market price for sell quoting
     *   onSubmit(autoTopUp) — handler invoked when either button is clicked
     */
    function init(opts) {
        const form = opts.form;
        const stakeInput = opts.stakeInput;
        const primaryBtn = opts.primaryBtn;
        const tobyBtn = opts.tobyBtn;
        const balanceEl = opts.balanceEl;
        const onSubmit = opts.onSubmit;
        let tobyCoins = Number(opts.tobyCoins) || 0;
        let marketPrice = Number(opts.marketPrice) || 0;

        if (!form || !stakeInput || !tobyBtn) return null;

        const coinsLabel = tobyBtn.querySelector('.casino-bet-toby-coins');

        function liveBalance() {
            if (!balanceEl) return 0;
            const parsed = parseInt(balanceEl.textContent, 10);
            return isNaN(parsed) ? 0 : parsed;
        }

        function refresh() {
            const stake = parseInt(stakeInput.value, 10);
            const balance = liveBalance();
            if (!stake || stake <= 0 || balance >= stake) {
                // Either no stake yet or credits already cover it — only
                // the primary button is relevant.
                tobyBtn.hidden = true;
                return;
            }
            const shortfall = stake - balance;
            const coinsNeeded = coinsNeededForShortfall(shortfall, marketPrice, tobyCoins);
            if (marketPrice <= 0 || coinsNeeded > tobyCoins) {
                // Either no market yet or the player's TOBY can't cover.
                tobyBtn.hidden = true;
                return;
            }
            if (coinsLabel) coinsLabel.textContent = String(coinsNeeded);
            tobyBtn.hidden = false;
        }

        function setTobyCoins(n) {
            tobyCoins = Number(n) || 0;
            refresh();
        }

        function setMarketPrice(p) {
            marketPrice = Number(p) || 0;
            refresh();
        }

        // Track which button initiated the submit so the form handler
        // can pass autoTopUp through to the caller.
        let lastClickedToby = false;
        if (primaryBtn) {
            primaryBtn.addEventListener('click', function () { lastClickedToby = false; });
        }
        tobyBtn.addEventListener('click', function () { lastClickedToby = true; });

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (typeof onSubmit === 'function') onSubmit(lastClickedToby);
            lastClickedToby = false;
        });

        stakeInput.addEventListener('input', refresh);
        refresh();

        return {
            refresh: refresh,
            setTobyCoins: setTobyCoins,
            setMarketPrice: setMarketPrice,
        };
    }

    /** Tiny formatter for the "Sold N TOBY @ price" prefix on result lines. */
    function soldPrefixHtml(soldTobyCoins, newPrice) {
        if (!soldTobyCoins || soldTobyCoins <= 0) return '';
        const priceText = (typeof newPrice === 'number') ? ' @ ' + newPrice.toFixed(2) : '';
        return '<small class="casino-bet-toby-sold">Sold ' + soldTobyCoins +
            ' TOBY' + priceText + '</small><br>';
    }

    const api = {
        init: init,
        proceedsForSell: proceedsForSell,
        coinsNeededForShortfall: coinsNeededForShortfall,
        soldPrefixHtml: soldPrefixHtml,
    };

    if (root) root.TobyTopUp = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
