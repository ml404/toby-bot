// Shared helpers for the per-guild casino jackpot pool. Each minigame's
// result-rendering JS prepends a "🎰 JACKPOT!" banner when the spin /
// flip / roll / play / scratch hit the jackpot, so the visual treatment
// stays identical across slots, coinflip, dice, highlow, and scratch.

(function (root) {
    'use strict';

    function isJackpotHit(body) {
        return !!(body && typeof body.jackpotPayout === 'number' && body.jackpotPayout > 0);
    }

    /**
     * Tier label keyed off the percentage of pool the wheel picked.
     * Mirrors `casino-jackpot-wheel.js#tierLabel` so the result line
     * and the wheel's settle text say the same thing.
     */
    function tierLabel(payoutPct) {
        if (typeof payoutPct !== 'number' || payoutPct <= 0) return '🎰 JACKPOT!';
        if (payoutPct >= 30) return '🎰 MEGA JACKPOT!';
        if (payoutPct >= 10) return '🎰 BIG WIN!';
        if (payoutPct >= 2)  return '💰 Nice payout!';
        return '🎟️ Pity prize';
    }

    function jackpotPrefixHtml(payout, payoutPct) {
        if (typeof payout !== 'number' || payout <= 0) return '';
        return tierLabel(payoutPct) + ' +' + payout + ' credits<br>';
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
        // `jackpotTierPayoutPct` arrives as a fraction (0..1) from the
        // backend; render the percent so it matches the wheel's labels.
        const pct = typeof body.jackpotTierPayoutPct === 'number'
            ? body.jackpotTierPayoutPct * 100
            : 0;
        return jackpotPrefixHtml(body.jackpotPayout, pct) + winLineHtml;
    }

    /**
     * Spin the visual wheel to the server-picked tier. No-op when the
     * response isn't a jackpot hit or the wheel module isn't loaded
     * (e.g. test harnesses, mid-migration pages). `onSettle` fires
     * once the wheel stops so the caller can paint its result line in
     * sync with the wheel reveal.
     */
    function spinWheelFor(body, onSettle) {
        if (!isJackpotHit(body) || typeof body.jackpotTierIndex !== 'number' || body.jackpotTierIndex < 0) {
            if (typeof onSettle === 'function') onSettle();
            return;
        }
        const wheel = root && root.TobyJackpotWheel;
        if (!wheel || typeof wheel.spinTo !== 'function') {
            if (typeof onSettle === 'function') onSettle();
            return;
        }
        const pct = typeof body.jackpotTierPayoutPct === 'number'
            ? Math.round(body.jackpotTierPayoutPct * 100)
            : 0;
        wheel.spinTo(body.jackpotTierIndex, body.jackpotPayout, pct, onSettle);
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
        tierLabel,
        renderWinHtml,
        spinWheelFor,
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
