// Pure-DOM render for a /spin response. Hoisted out of the IIFE so the
// jest test in `slots.test.js` can drive it without booting the whole
// page. The IIFE below calls it with the live result element.
function renderSlotsResult(resultEl, body, flashTargetEl, reels) {
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
    if (typeof window === 'undefined' || !body) return;
    // Light up the winning reels with the gold halo used everywhere else
    // for "this is the active payoff", and drop the shared chip flourish
    // on the felt so a slots win celebrates like a blackjack win.
    if (reels && reels.length) {
        reels.forEach(function (r) { if (r) r.classList.remove('win-cell'); });
        if (body.win) {
            reels.forEach(function (r) { if (r) r.classList.add('win-cell'); });
        }
    }
    if (window.CasinoRender) {
        // Bigger payouts get a taller stack, capped internally at 7.
        var payoutEstimate = (body.jackpotPayout > 0 ? body.jackpotPayout : body.net) || 0;
        var chipCount = Math.min(7, Math.max(3, Math.ceil(payoutEstimate / 100)));
        window.CasinoRender.flashWinPayout(flashTargetEl, body, chipCount);
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
    const machineEl = document.querySelector('.slots-machine');
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
        // Strip any prior win highlight so a fresh spin doesn't wear the
        // gold halo from the previous round while it's still tumbling.
        reels.forEach(function (reel) {
            reel.classList.remove('win-cell');
            reel.classList.add('spinning');
        });
        if (window.CasinoSounds) window.CasinoSounds.play('deal');
        return setInterval(function () {
            reels.forEach(function (reel) {
                reel.textContent = SPIN_SYMBOLS[Math.floor(Math.random() * SPIN_SYMBOLS.length)];
            });
        }, SPIN_INTERVAL_MS);
    }

    function stopSpinAnimation(intervalId, body) {
        clearInterval(intervalId);
        const finalSymbols = body && body.symbols;
        // Stagger the reel-stop click cues so each reel locking in is
        // audible — like a slot machine's individual reel ticks.
        reels.forEach(function (reel, i) {
            reel.classList.remove('spinning');
            if (finalSymbols && finalSymbols[i]) reel.textContent = finalSymbols[i];
            if (window.CasinoSounds) {
                setTimeout(function () { window.CasinoSounds.play('click'); }, i * 110);
            }
        });
        if (body && window.CasinoSounds) {
            setTimeout(function () {
                window.CasinoSounds.play(body.net > 0 ? 'win' : 'lose');
            }, reels.length * 110 + 80);
        }
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
        renderResult: function (body) { renderSlotsResult(resultEl, body, machineEl, reels); },
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderSlotsResult };
}
