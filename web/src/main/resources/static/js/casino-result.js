// Shared scaffolding for every minigame's result-rendering function:
//   - clear the three game-prefixed result classes (-win, -lose, -jackpot)
//   - prepend the TobyTopUp sold-coins prefix
//   - either wrap the win line via TobyJackpot.renderWinHtml (jackpot
//     banner if it hit) OR append TobyJackpot.lossTributeSuffix to the
//     lose line.
//
// Each game's renderXResult used to open-code that exact dance with
// only the inner win/lose HTML differing. Now they hand us those two
// HTML fragments and the class prefix; we do the rest.

(function (root) {
    'use strict';

    /**
     * options:
     *   resultEl:     DOM element receiving innerHTML (no-op if null)
     *   body:         server response object (must include `win`, may
     *                 include soldTobyCoins/newPrice/jackpotPayout/lossTribute)
     *   classPrefix:  e.g. 'slots' for 'slots-result-win'/'-lose'/'-jackpot'
     *   winLineHtml:  HTML string for the win line (game-specific)
     *   loseLineHtml: HTML string for the lose line (game-specific)
     */
    function render(opts) {
        const el = opts.resultEl;
        if (!el) return;
        const prefix = opts.classPrefix;
        el.hidden = false;
        el.classList.remove(
            prefix + '-result-win',
            prefix + '-result-lose',
            prefix + '-result-jackpot'
        );
        const topUpPrefix = (root && root.TobyTopUp)
            ? root.TobyTopUp.soldPrefixHtml(opts.body.soldTobyCoins, opts.body.newPrice)
            : '';
        if (opts.body.win) {
            el.classList.add(prefix + '-result-win');
            const withJackpot = (root && root.TobyJackpot)
                ? root.TobyJackpot.renderWinHtml(el, opts.body, prefix + '-result-jackpot', opts.winLineHtml)
                : opts.winLineHtml;
            el.innerHTML = topUpPrefix + withJackpot;
        } else {
            el.classList.add(prefix + '-result-lose');
            const tributeSuffix = (root && root.TobyJackpot)
                ? root.TobyJackpot.lossTributeSuffix(opts.body)
                : '';
            el.innerHTML = topUpPrefix + opts.loseLineHtml + tributeSuffix;
        }
    }

    const api = { render: render };
    if (root) root.TobyCasinoResult = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
