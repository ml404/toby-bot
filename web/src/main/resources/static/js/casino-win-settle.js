// Shared win-settle for casino minigames. Owns the "play win or lose
// sound + drop a chip flourish on the felt" pair that every minigame
// used to do by hand at the end of its renderResult / stopAnimation.
// Centralising it means a future game can't ship without the cue, and
// a tweak to the formula (chip count, payout selection, push-vs-win
// handling) is one edit instead of nine.
//
// API:
//   TobyCasinoWinSettle.fire(body, flashTarget, options?)
//     body         — server response. Must include either `win` (bool)
//                    or `net` (number); push (`body.push: true`)
//                    suppresses both sound and flash.
//     flashTarget  — DOM element receiving the chip stack, or null to
//                    skip the visual half (sound only).
//     options.chipCount — optional `(body) => number` overriding the
//                    default scaling (3–7 chips, scaling roughly by
//                    `payout / 100`).
//
// Tolerant of missing dependencies — silently no-ops if `CasinoSounds`
// or `CasinoRender` haven't been loaded yet.

(function (root) {
    'use strict';

    var DEFAULT_MIN_CHIPS = 3;
    var DEFAULT_MAX_CHIPS = 7;

    function defaultChipCount(body) {
        var payout = (typeof body.jackpotPayout === 'number' && body.jackpotPayout > 0)
            ? body.jackpotPayout
            : (body.net || 0);
        return Math.min(
            DEFAULT_MAX_CHIPS,
            Math.max(DEFAULT_MIN_CHIPS, Math.ceil(payout / 100))
        );
    }

    function fire(body, flashTarget, options) {
        if (!body) return;
        // Push (baccarat tie + future tie-able games) — refunded stake,
        // neither outcome. Stay silent.
        if (body.push) return;

        var isWin = !!body.win || (body.net || 0) > 0;
        if (root && root.CasinoSounds) {
            root.CasinoSounds.play(isWin ? 'win' : 'lose');
        }

        if (!isWin || !flashTarget || !(root && root.CasinoRender)) return;

        // flashWinPayout requires body.win to be truthy. Some games
        // (highlow / scratch) don't return a `win` field — they use
        // net > 0. Synthesise a body so the visual half lands either way.
        var paintBody = body.win ? body : Object.assign({}, body, { win: true });
        var fn = (options && typeof options.chipCount === 'function')
            ? options.chipCount
            : defaultChipCount;
        root.CasinoRender.flashWinPayout(flashTarget, paintBody, fn(paintBody));
    }

    var api = { fire: fire, defaultChipCount: defaultChipCount };
    if (root) root.TobyCasinoWinSettle = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
