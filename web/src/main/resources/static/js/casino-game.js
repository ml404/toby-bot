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

            postJson(endpoint, payload)
                .then(function (body) {
                    const elapsed = Date.now() - requestStart;
                    const remaining = Math.max(0, minSettleMs - elapsed);
                    setTimeout(function () {
                        stopAnimation(animationHandle, body);
                        busy = false;
                        setDisabled(false);
                        if (body && body.ok) {
                            renderResult(body);
                            if (autoApplyBalance) {
                                applyBalance(body.newBalance);
                                applyTobyDelta(body);
                            }
                        } else {
                            hideResult();
                            showToast((body && body.error) || failureMessage, 'error');
                        }
                    }, remaining);
                })
                .catch(function () {
                    stopAnimation(animationHandle, null);
                    busy = false;
                    setDisabled(false);
                    hideResult();
                    showToast('Network error.', 'error');
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

    const api = { init: init };
    if (root) root.TobyCasinoGame = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
