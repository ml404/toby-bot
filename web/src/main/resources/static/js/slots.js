// Pure-DOM render for a /spin response. Hoisted out of the IIFE so the
// jest test in `slots.test.js` can drive it without booting the whole
// page. The IIFE below calls it with the live result element.
function renderSlotsResult(resultEl, body) {
    if (!resultEl) return;
    resultEl.hidden = false;
    resultEl.classList.remove('slots-result-win', 'slots-result-lose', 'slots-result-jackpot');
    const topUpPrefix = (typeof window !== 'undefined' && window.TobyTopUp)
        ? window.TobyTopUp.soldPrefixHtml(body.soldTobyCoins, body.newPrice)
        : '';
    if (body.win) {
        resultEl.classList.add('slots-result-win');
        const winLine = '<strong>+' + body.net + ' credits</strong> &middot; ' +
            body.multiplier + '× on a stake';
        const withJackpot = (typeof window !== 'undefined' && window.TobyJackpot)
            ? window.TobyJackpot.renderWinHtml(resultEl, body, 'slots-result-jackpot', winLine)
            : winLine;
        resultEl.innerHTML = topUpPrefix + withJackpot;
    } else {
        resultEl.classList.add('slots-result-lose');
        resultEl.innerHTML = topUpPrefix + 'Lost <strong>' + Math.abs(body.net) + ' credits</strong>';
    }
}

(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const initialTobyCoins = Number(main.dataset.tobyCoins) || 0;
    const initialMarketPrice = Number(main.dataset.marketPrice) || 0;
    const postJson = window.TobyApi && window.TobyApi.postJson;

    function toast(msg, type) {
        if (window.TobyToast && typeof window.TobyToast.show === 'function') {
            window.TobyToast.show(msg, { type: type || 'info' });
        } else {
            console.log('[' + (type || 'info') + '] ' + msg);
        }
    }

    const reels = [
        document.getElementById('slots-reel-0'),
        document.getElementById('slots-reel-1'),
        document.getElementById('slots-reel-2')
    ];
    const stakeInput = document.getElementById('slots-stake');
    const spinBtn = document.getElementById('slots-spin');
    const spinTobyBtn = document.getElementById('slots-spin-toby');
    const balanceEl = document.getElementById('slots-balance');
    const resultEl = document.getElementById('slots-result');
    const form = document.getElementById('slots-bet');

    if (!form || !spinBtn || !stakeInput || reels.some(function (r) { return !r; })) return;

    const topUp = (window.TobyTopUp && spinTobyBtn) ? window.TobyTopUp.init({
        form: form,
        stakeInput: stakeInput,
        primaryBtn: spinBtn,
        tobyBtn: spinTobyBtn,
        balanceEl: balanceEl,
        tobyCoins: initialTobyCoins,
        marketPrice: initialMarketPrice,
        onSubmit: function (autoTopUp) { runSpin(autoTopUp); },
    }) : null;

    const SPIN_SYMBOLS = ['🍒', '🍋', '🔔', '⭐'];
    const SPIN_INTERVAL_MS = 60;
    const SPIN_DURATION_MS = 800;

    let spinning = false;

    function startSpinAnimation() {
        spinning = true;
        reels.forEach(function (reel) { reel.classList.add('spinning'); });
        return setInterval(function () {
            reels.forEach(function (reel) {
                reel.textContent = SPIN_SYMBOLS[Math.floor(Math.random() * SPIN_SYMBOLS.length)];
            });
        }, SPIN_INTERVAL_MS);
    }

    function stopSpinAnimation(intervalId, finalSymbols) {
        clearInterval(intervalId);
        spinning = false;
        reels.forEach(function (reel, i) {
            reel.classList.remove('spinning');
            if (finalSymbols && finalSymbols[i]) reel.textContent = finalSymbols[i];
        });
    }

    function applyBalance(newBalance) {
        if (typeof newBalance !== 'number') return;
        if (balanceEl) balanceEl.textContent = newBalance;
    }

    function applyTobyDelta(body) {
        // After a successful spin (top-up or otherwise) refresh the
        // helper's tobyCoins so the second-button state reflects the
        // post-sale wallet.
        if (!topUp) return;
        if (typeof body.soldTobyCoins === 'number') {
            const remaining = Math.max(0, initialTobyCoins - body.soldTobyCoins);
            topUp.setTobyCoins(remaining);
        }
        if (typeof body.newPrice === 'number') topUp.setMarketPrice(body.newPrice);
    }

    function runSpin(autoTopUp) {
        if (spinning) return;

        const stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            toast('Enter a positive stake.', 'error');
            return;
        }

        spinBtn.disabled = true;
        if (spinTobyBtn) spinTobyBtn.disabled = true;
        const intervalId = startSpinAnimation();
        const requestStart = Date.now();

        if (!postJson) {
            stopSpinAnimation(intervalId);
            spinBtn.disabled = false;
            if (spinTobyBtn) spinTobyBtn.disabled = false;
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        postJson('/casino/' + guildId + '/slots/spin', { stake: stake, autoTopUp: !!autoTopUp })
            .then(function (body) {
                const elapsed = Date.now() - requestStart;
                const remaining = Math.max(0, SPIN_DURATION_MS - elapsed);
                setTimeout(function () {
                    stopSpinAnimation(intervalId, body && body.symbols);
                    spinBtn.disabled = false;
                    if (spinTobyBtn) spinTobyBtn.disabled = false;
                    if (body && body.ok) {
                        renderSlotsResult(resultEl, body);
                        applyBalance(body.newBalance);
                        applyTobyDelta(body);
                    } else {
                        if (resultEl) resultEl.hidden = true;
                        toast((body && body.error) || 'Spin failed.', 'error');
                    }
                }, remaining);
            })
            .catch(function () {
                stopSpinAnimation(intervalId);
                spinBtn.disabled = false;
                if (spinTobyBtn) spinTobyBtn.disabled = false;
                if (resultEl) resultEl.hidden = true;
                toast('Network error.', 'error');
            });
    }

    if (!topUp) {
        // No TobyTopUp helper loaded — the page still works without
        // the auto-topup flow, plain submit posts a normal spin.
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            runSpin(false);
        });
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderSlotsResult };
}
