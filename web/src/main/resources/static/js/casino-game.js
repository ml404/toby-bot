// Shared scaffolding for the casino-game pages — every minigame
// (dice, coinflip, slots, scratch, highlow) used to copy the same
// validation/fetch/spinner/toast/balance-update plumbing. Centralising
// it here means a tweak (e.g. a different toast on network errors,
// changing the disabled-button protocol, swapping postJson for a new
// API helper) is one edit instead of five.
//
// Per-game JS now provides only the bits that are actually different:
//   - which input fields make up the request payload (buildPayload)
//   - any extra validation beyond "stake > 0" (validate)
//   - the settle animation hook (startAnimation / stopAnimation)
//   - the win/lose result renderer (renderResult)
//
// The helper handles: TobyTopUp wiring, form submit, button disabling,
// fetch, settle-animation timing, balance + topup-coin updates, error
// toasts, and the "API helper missing" / network-error fallbacks.

(function (root) {
    'use strict';

    function init(cfg) {
        const form = cfg.form;
        const stakeInput = cfg.stakeInput;
        const primaryBtn = cfg.primaryBtn;
        const tobyBtn = cfg.tobyBtn;
        const balanceEl = cfg.balanceEl;
        const resultEl = cfg.resultEl;
        const guildId = cfg.guildId;
        const endpoint = cfg.endpoint;
        const initialTobyCoins = Number(cfg.tobyCoins) || 0;
        const initialMarketPrice = Number(cfg.marketPrice) || 0;
        const minSettleMs = (typeof cfg.minSettleMs === 'number') ? cfg.minSettleMs : 0;
        const failureMessage = cfg.failureMessage || 'Request failed.';
        const postJson = (root && root.TobyApi) ? root.TobyApi.postJson : null;

        const buildPayload = cfg.buildPayload || defaultBuildPayload;
        const validate = cfg.validate || (() => null);
        const startAnimation = cfg.startAnimation || (() => null);
        const stopAnimation = cfg.stopAnimation || (() => undefined);
        const renderResult = cfg.renderResult || (() => undefined);
        // Scratch defers balance + topup-coin updates until the player has
        // revealed every cell (so credits don't visibly drop before the
        // suspense beat is over). It opts out and updates manually.
        const autoApplyBalance = cfg.autoApplyBalance !== false;
        // Win-settle: every minigame plays a win/lose cue + (on win) a
        // chip flourish on the felt. The helper module centralises both.
        // Pass `flashTarget` (a DOM element or `() => DOM element`) and
        // the helper will auto-fire after renderResult settles. Passing
        // null skips the visual half but the win/lose sound still plays
        // (audio-only parity is the floor — every game gets the cue).
        const flashTargetFn = (typeof cfg.flashTarget === 'function')
            ? cfg.flashTarget
            : (cfg.flashTarget ? () => cfg.flashTarget : () => null);
        const chipCountFn = (typeof cfg.chipCount === 'function') ? cfg.chipCount : null;
        // Some custom flows (scratch's user-driven reveal) drive the
        // win-settle themselves — `autoWinSettle: false` opts out so
        // the helper doesn't double-fire.
        const autoWinSettle = cfg.autoWinSettle !== false;

        if (!form || !stakeInput || !primaryBtn) {
            return { run: function () {} };
        }

        let busy = false;

        const topUp = (root.TobyTopUp && tobyBtn) ? root.TobyTopUp.init({
            form: form,
            stakeInput: stakeInput,
            primaryBtn: primaryBtn,
            tobyBtn: tobyBtn,
            balanceEl: balanceEl,
            tobyCoins: initialTobyCoins,
            marketPrice: initialMarketPrice,
            onSubmit: function (autoTopUp) { run(!!autoTopUp); },
        }) : null;

        if (!topUp) {
            // No TOBY top-up button on this page — just hook the form's
            // primary submit so a single-button page still works.
            form.addEventListener('submit', function (e) {
                e.preventDefault();
                run(false);
            });
        }

        function setDisabled(disabled) {
            primaryBtn.disabled = disabled;
            if (tobyBtn) tobyBtn.disabled = disabled;
        }

        function applyBalance(newBalance) {
            // Delegates to the shared module so every casino page funnels
            // wallet writes through one site (animations, formatting, etc.).
            // Falls back to the inline write if the shared module didn't
            // load (older test paths that don't require it).
            if (root && root.TobyBalance) {
                root.TobyBalance.update(balanceEl, newBalance);
            } else if (typeof newBalance === 'number' && balanceEl) {
                balanceEl.textContent = newBalance;
            }
            // The "Bet (sell TOBY)" button's visibility is driven by
            // stake-vs-balance. Without this nudge a player who was short
            // before a win keeps the secondary button on screen even
            // after their balance climbs past the stake.
            if (topUp) topUp.refresh();
        }

        function applyWinSettle(body, override) {
            // Plays the win/lose cue + drops a chip flourish via the
            // shared helper. `override` lets a custom flow (scratch's
            // reveal) supply a fresh body / target / chipCount without
            // reaching into the helper's API directly.
            if (!root || !root.TobyCasinoWinSettle) return;
            var target = (override && 'flashTarget' in override)
                ? override.flashTarget
                : flashTargetFn(body);
            var opts = (override && override.chipCount)
                ? { chipCount: override.chipCount }
                : (chipCountFn ? { chipCount: chipCountFn } : null);
            root.TobyCasinoWinSettle.fire(body, target, opts);
        }

        function applyTobyDelta(body) {
            if (!topUp) return;
            if (typeof body.soldTobyCoins === 'number') {
                const remaining = Math.max(0, initialTobyCoins - body.soldTobyCoins);
                topUp.setTobyCoins(remaining);
            }
            if (typeof body.newPrice === 'number') topUp.setMarketPrice(body.newPrice);
            // Belt-and-suspenders for scratch's manual flow — it calls
            // applyTobyDelta after the balance write so the secondary
            // button re-evaluates against the post-reveal wallet even
            // when the response carried no soldTobyCoins/newPrice.
            topUp.refresh();
        }

        function showToast(message, type) {
            const t = root.toast;
            if (typeof t === 'function') t(message, type || 'info');
        }

        function hideResult() {
            if (resultEl) resultEl.hidden = true;
        }

        function run(autoTopUp) {
            if (busy) return;

            const validationError = validate({
                stake: parseInt(stakeInput.value, 10),
                form: form,
            });
            if (typeof validationError === 'string') {
                showToast(validationError, 'error');
                return;
            }

            const stake = parseInt(stakeInput.value, 10);
            if (!stake || stake <= 0) {
                showToast('Enter a positive stake.', 'error');
                return;
            }

            if (!postJson) {
                showToast('API helper missing — refresh the page.', 'error');
                return;
            }

            const payload = buildPayload({
                stake: stake,
                autoTopUp: !!autoTopUp,
                form: form,
            });

            busy = true;
            setDisabled(true);
            const animationHandle = startAnimation();
            const requestStart = Date.now();
            // Hold the central pool-banner refresh (api.js feeds it from
            // X-Jackpot-Pool the moment the response lands, which would
            // otherwise leak the outcome before the reels/coin/cards
            // settle). Pages without TobyJackpot just skip silently.
            // Scratch opts out — its lock spans the user-driven reveal
            // and is managed inside scratch.js itself.
            const jackpot = (autoApplyBalance && root && root.TobyJackpot) ? root.TobyJackpot : null;
            if (jackpot) jackpot.holdPoolBanner();

            postJson(endpoint, payload)
                .then(function (body) {
                    const elapsed = Date.now() - requestStart;
                    const remaining = Math.max(0, minSettleMs - elapsed);
                    setTimeout(function () {
                        stopAnimation(animationHandle, body);
                        if (body && body.ok) {
                            // renderResult may run a staggered reveal
                            // (keno's draw, baccarat's deal) and return a
                            // Promise that resolves once the last cell /
                            // card lands. We hold the busy lock + button
                            // disable until the animation completes too —
                            // re-enabling the Deal button mid-reveal lets
                            // an auto-clicker fire a second hand on top
                            // of the reveal of the first (keno was the
                            // worst offender at 8 draws × 120ms = ~1s of
                            // unguarded reveal). Synchronous renderResults
                            // (slots/coinflip/dice) return undefined and
                            // settle immediately as before.
                            const settle = renderResult(body);
                            const finishSettle = function () {
                                if (autoApplyBalance) {
                                    applyBalance(body.newBalance);
                                    applyTobyDelta(body);
                                }
                                if (autoWinSettle) {
                                    applyWinSettle(body);
                                }
                                if (jackpot) jackpot.releasePoolBanner();
                                busy = false;
                                setDisabled(false);
                            };
                            if (settle && typeof settle.then === 'function') {
                                settle.then(finishSettle, finishSettle);
                            } else {
                                finishSettle();
                            }
                        } else {
                            hideResult();
                            showToast((body && body.error) || failureMessage, 'error');
                            if (jackpot) jackpot.releasePoolBanner();
                            busy = false;
                            setDisabled(false);
                        }
                    }, remaining);
                })
                .catch(function () {
                    stopAnimation(animationHandle, null);
                    busy = false;
                    setDisabled(false);
                    hideResult();
                    showToast('Network error.', 'error');
                    if (jackpot) jackpot.releasePoolBanner();
                });
        }

        function defaultBuildPayload(state) {
            return { stake: state.stake, autoTopUp: state.autoTopUp };
        }

        return {
            run: run,
            applyBalance: applyBalance,
            applyTobyDelta: applyTobyDelta,
            applyWinSettle: applyWinSettle,
            isBusy: function () { return busy; },
        };
    }

    // Manual lifecycle helper for casino flows that don't fit the
    // form-submit scaffold (e.g. highlow's /play button pair). Mirrors
    // init()'s jackpot-pool-lock + settle-delay handling so the
    // banner can't tick before the per-flow animation lands. Caller
    // owns the request, the per-call rendering, balance updates, and
    // any button-disable bookkeeping.
    //
    // opts:
    //   request    — () => Promise<body> (REQUIRED). Caller's fetch.
    //   settleMs   — number (default 0). Minimum visual delay between
    //                request start and onSettle firing, mirroring
    //                init()'s minSettleMs.
    //   onSettle   — (body) => void | Promise<void>. Runs once the
    //                settle delay has elapsed, with whatever the
    //                request resolved to (success or `{ ok: false }`).
    //                If it returns a thenable the lock release waits
    //                for it (parallels init()'s async-renderResult
    //                support — keno/baccarat-shape).
    //   onError    — (err) => void. Runs synchronously on a request
    //                rejection (network / parse failure).
    function runManual(opts) {
        const request = opts.request;
        const settleMs = (typeof opts.settleMs === 'number') ? opts.settleMs : 0;
        const onSettle = opts.onSettle || function () {};
        const onError = opts.onError || function () {};

        const jackpot = (root && root.TobyJackpot) ? root.TobyJackpot : null;
        if (jackpot) jackpot.holdPoolBanner();
        const start = Date.now();
        const release = function () {
            if (jackpot) jackpot.releasePoolBanner();
        };

        return request().then(function (body) {
            const remaining = Math.max(0, settleMs - (Date.now() - start));
            setTimeout(function () {
                let settled;
                try {
                    settled = onSettle(body);
                } catch (e) {
                    // Release the lock even if the caller's render
                    // logic throws — otherwise the banner is stranded.
                    // Surface the error via console so a regression
                    // is still visible during dev.
                    release();
                    if (typeof console !== 'undefined' && console.error) {
                        console.error('TobyCasinoGame.runManual onSettle threw:', e);
                    }
                    return;
                }
                if (settled && typeof settled.then === 'function') {
                    settled.then(release, release);
                } else {
                    release();
                }
            }, remaining);
        }, function (err) {
            try { onError(err); }
            finally { release(); }
        });
    }

    const api = { init: init, runManual: runManual };
    if (root) root.TobyCasinoGame = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
