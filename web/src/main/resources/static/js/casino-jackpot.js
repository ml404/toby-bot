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

    // Hold/release lock used by per-game JS to keep the pool banner in
    // sync with reveal animations. While held, updatePoolBanner queues
    // the latest body instead of painting (last-write-wins) so a player
    // can't read the outcome from the banner ticking before the reels
    // stop / cards land / scratch cells reveal. release flushes the
    // queued body. Pages without an animation never call hold so the
    // existing immediate-paint behaviour is preserved.
    let locked = false;
    let pending = null;

    function paintPoolBanner(body) {
        if (typeof document === 'undefined') return;
        const target = document.querySelector('.casino-jackpot-banner strong');
        if (target) target.textContent = String(body.jackpotPool);
    }

    /**
     * Refresh the per-guild jackpot pool banner (`fragments/casino.html`)
     * from a `body.jackpotPool` value. Called centrally by api.js after
     * every casino POST, fed from the X-Jackpot-Pool response header — so
     * games never have to remember to mirror the pool themselves. No-ops
     * when the field is absent or the banner isn't on the current page (so
     * duel/trade/tip pages silently skip the DOM write). When the banner
     * is held (see holdPoolBanner) the value is stashed and painted on
     * release instead.
     */
    function updatePoolBanner(body) {
        if (!body || typeof body.jackpotPool !== 'number') return;
        if (locked) { pending = body; return; }
        paintPoolBanner(body);
    }

    function holdPoolBanner() {
        locked = true;
    }

    function releasePoolBanner() {
        locked = false;
        if (pending) {
            const body = pending;
            pending = null;
            paintPoolBanner(body);
        }
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

    const api = {
        isJackpotHit,
        jackpotPrefixHtml,
        renderWinHtml,
        lossTributeSuffix,
        updatePoolBanner,
        holdPoolBanner,
        releasePoolBanner,
    };

    // Browser global so each game's IIFE-style JS can reach it without
    // bundler plumbing.
    if (root) root.TobyJackpot = api;

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
