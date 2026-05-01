// Pure-DOM render for a /spin response. Hoisted out of the IIFE so the
// jest test in `slots.test.js` can drive it without booting the whole
// page. The IIFE below calls it with the live result element.
function renderSlotsResult(resultEl, body) {
    if (typeof window !== 'undefined' && window.TobyCasinoResult) {
        window.TobyCasinoResult.render({
            resultEl: resultEl,
            body: body,
            classPrefix: 'slots',
            winLineHtml: '<strong>+' + body.net + ' credits</strong> &middot; ' +
                body.multiplier + '× on a stake',
            loseLineHtml: 'Lost <strong>' + Math.abs(body.net) + ' credits</strong>',
        });
    }
}

(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
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

    const SPIN_SYMBOLS = ['🍒', '🍋', '🔔', '⭐'];
    const SPIN_INTERVAL_MS = 60;
    const SPIN_DURATION_MS = 800;

    function startSpinAnimation() {
        reels.forEach(function (reel) { reel.classList.add('spinning'); });
        return setInterval(function () {
            reels.forEach(function (reel) {
                reel.textContent = SPIN_SYMBOLS[Math.floor(Math.random() * SPIN_SYMBOLS.length)];
            });
        }, SPIN_INTERVAL_MS);
    }

    function stopSpinAnimation(intervalId, body) {
        clearInterval(intervalId);
        const finalSymbols = body && body.symbols;
        reels.forEach(function (reel, i) {
            reel.classList.remove('spinning');
            if (finalSymbols && finalSymbols[i]) reel.textContent = finalSymbols[i];
        });
    }

    window.TobyCasinoGame.init({
        guildId: guildId,
        endpoint: '/casino/' + guildId + '/slots/spin',
        form: form,
        stakeInput: stakeInput,
        primaryBtn: spinBtn,
        tobyBtn: spinTobyBtn,
        balanceEl: balanceEl,
        resultEl: resultEl,
        tobyCoins: Number(main.dataset.tobyCoins) || 0,
        marketPrice: Number(main.dataset.marketPrice) || 0,
        minSettleMs: SPIN_DURATION_MS,
        failureMessage: 'Spin failed.',
        startAnimation: startSpinAnimation,
        stopAnimation: stopSpinAnimation,
        renderResult: function (body) { renderSlotsResult(resultEl, body); },
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderSlotsResult };
}
