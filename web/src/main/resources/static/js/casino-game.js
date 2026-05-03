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
            if (typeof newBalance !== 'number') return;
            if (balanceEl) balanceEl.textContent = newBalance;
        }

        function applyTobyDelta(body) {
            if (!topUp) return;
            if (typeof body.soldTobyCoins === 'number') {
                const remaining = Math.max(0, initialTobyCoins - body.soldTobyCoins);
                topUp.setTobyCoins(remaining);
            }
            if (typeof body.newPrice === 'number') topUp.setMarketPrice(body.newPrice);
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
                        busy = false;
                        setDisabled(false);
                        if (body && body.ok) {
                            // renderResult may run a staggered reveal
                            // (keno's draw, baccarat's deal) and return a
                            // Promise that resolves once the last cell /
                            // card lands. In that case we hold the lock,
                            // balance, and TOBY-coin update until the
                            // animation actually completes — otherwise
                            // the banner / credits would tick first and
                            // leak the outcome. Synchronous renderResults
                            // (slots/coinflip/dice) return undefined and
                            // settle immediately as before.
                            const settle = renderResult(body);
                            const finishSettle = function () {
                                if (autoApplyBalance) {
                                    applyBalance(body.newBalance);
                                    applyTobyDelta(body);
                                }
                                if (jackpot) jackpot.releasePoolBanner();
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
