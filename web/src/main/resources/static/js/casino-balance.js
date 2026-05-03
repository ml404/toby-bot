// Tiny shared helper for "set the displayed wallet balance" — used by
// every casino page that updates a `*-balance` element from a server
// response's `newBalance` field. Mirrors the TobyJackpot module shape so
// that page-specific JS can call one canonical write site instead of
// each file rolling its own `if (b == null) return; el.textContent = b`.
//
// Game-specific layout (`#bj-balance`, `#poker-balance`, `#tip-balance`,
// the standardised `els.balanceEl` from casino-minigame-dom.js) all funnel
// through here, so a future tweak to balance presentation (animation,
// currency formatting, balance-flash on big swings) is one edit.
(function (root) {
    'use strict';

    /**
     * Write `newBalance` into [el].textContent. No-ops cleanly when the
     * element is missing (page without a wallet readout) or when the
     * server omitted the field — matches the existing one-shot game
     * helper's `typeof !== 'number'` guard so a stringified number from a
     * misbehaving backend doesn't end up as literal "[object Object]" or
     * "undefined" in the wallet.
     */
    function update(el, newBalance) {
        if (!el) return;
        if (typeof newBalance !== 'number') return;
        el.textContent = String(newBalance);
    }

    const api = { update };
    if (root) root.TobyBalance = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
