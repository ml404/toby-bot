// Pure-DOM render for a /spin response. Hoisted out of the IIFE so the
// jest test in `slots.test.js` can drive it without booting the whole
// page. The IIFE below calls it with the live result element.
function renderSlotsResult(resultEl, body) {
    if (!resultEl) return;
    resultEl.hidden = false;
    resultEl.classList.remove('slots-result-win', 'slots-result-lose', 'slots-result-jackpot');
    if (body.win) {
        resultEl.classList.add('slots-result-win');
        const winLine = '<strong>+' + body.net + ' credits</strong> &middot; ' +
            body.multiplier + '× on a stake';
        resultEl.innerHTML = (typeof window !== 'undefined' && window.TobyJackpot)
            ? window.TobyJackpot.renderWinHtml(resultEl, body, 'slots-result-jackpot', winLine)
            : winLine;
    } else {
        resultEl.classList.add('slots-result-lose');
        resultEl.innerHTML = 'Lost <strong>' + Math.abs(body.net) + ' credits</strong>';
    }
}

(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
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
    const balanceEl = document.getElementById('slots-balance');
    const resultEl = document.getElementById('slots-result');
    const form = document.getElementById('slots-bet');

    if (!form || !spinBtn || !stakeInput || reels.some(function (r) { return !r; })) return;

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

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        if (spinning) return;

        const stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            toast('Enter a positive stake.', 'error');
            return;
        }

        spinBtn.disabled = true;
        const intervalId = startSpinAnimation();
        const requestStart = Date.now();

        if (!postJson) {
            stopSpinAnimation(intervalId);
            spinBtn.disabled = false;
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        postJson('/casino/' + guildId + '/slots/spin', { stake: stake })
            .then(function (body) {
                const elapsed = Date.now() - requestStart;
                const remaining = Math.max(0, SPIN_DURATION_MS - elapsed);
                setTimeout(function () {
                    stopSpinAnimation(intervalId, body && body.symbols);
                    spinBtn.disabled = false;
                    if (body && body.ok) {
                        renderSlotsResult(resultEl, body);
                        applyBalance(body.newBalance);
                    } else {
                        if (resultEl) resultEl.hidden = true;
                        toast((body && body.error) || 'Spin failed.', 'error');
                    }
                }, remaining);
            })
            .catch(function () {
                stopSpinAnimation(intervalId);
                spinBtn.disabled = false;
                if (resultEl) resultEl.hidden = true;
                toast('Network error.', 'error');
            });
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderSlotsResult };
}
