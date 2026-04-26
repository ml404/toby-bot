// Shared helpers for the per-guild casino jackpot pool. Each minigame's
// result-rendering JS prepends a "🎰 JACKPOT!" banner when the spin /
// flip / roll / play / scratch hit the jackpot, so the visual treatment
// stays identical across slots, coinflip, dice, highlow, and scratch.

(function (root) {
    'use strict';

    function isJackpotHit(body) {
        return !!(body && typeof body.jackpotPayout === 'number' && body.jackpotPayout > 0);
    }

    function jackpotPrefixHtml(payout) {
        if (typeof payout !== 'number' || payout <= 0) return '';
        return '🎰 <strong>JACKPOT!</strong> +' + payout + ' credits<br>';
    }

    /**
     * Apply jackpot styling + prefix on top of an existing win-line HTML
     * fragment. Returns the HTML the result element should display.
     * Pass the body straight from the server response.
     *
     *   element.innerHTML = renderWinHtml(resEl, body, 'slots-result-jackpot', winLine)
     */
    function renderWinHtml(resultEl, body, jackpotClassName, winLineHtml) {
        if (!isJackpotHit(body)) return winLineHtml;
        if (resultEl && jackpotClassName) resultEl.classList.add(jackpotClassName);
        return jackpotPrefixHtml(body.jackpotPayout) + winLineHtml;
    }

    /**
     * Suffix appended to a lose-line when the loss tributed credits
     * into the per-guild jackpot pool. Empty string when no tribute
     * (e.g. tiny stake floored to zero, or admin set tribute to 0 %).
     */
    function lossTributeSuffix(body) {
        if (!body || typeof body.lossTribute !== 'number' || body.lossTribute <= 0) return '';
        return ' &middot; <span class="casino-loss-tribute">+' +
            body.lossTribute + ' to jackpot</span>';
    }

    const api = { isJackpotHit, jackpotPrefixHtml, renderWinHtml, lossTributeSuffix };

    // Browser global so each game's IIFE-style JS can reach it without
    // bundler plumbing.
    if (root) root.TobyJackpot = api;

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
