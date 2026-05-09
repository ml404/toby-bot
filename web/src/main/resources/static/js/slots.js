// Pure-DOM render for a /spin response. Hoisted out of the IIFE so the
// jest test in `slots.test.js` can drive it without booting the whole
// page. The IIFE below calls it with the live result element.
//
// Win/lose sound + chip flourish on the felt are owned by the shared
// `casino-win-settle.js` helper, fired by casino-game.js after this
// renderResult returns (see flashTarget in init below). All slots-
// specific visuals (the gold halo on the winning reels) stay here.
function renderSlotsResult(resultEl, body, reels) {
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
    // for "this is the active payoff".
    if (reels && reels.length) {
        reels.forEach(function (r) { if (r) r.classList.remove('win-cell'); });
        if (body.win) {
            reels.forEach(function (r) { if (r) r.classList.add('win-cell'); });
        }
    }
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('slots', 'spin');
    if (!els) return;

    const reels = [
        document.getElementById('slots-reel-0'),
        document.getElementById('slots-reel-1'),
        document.getElementById('slots-reel-2')
    ];
    const machineEl = document.querySelector('.slots-machine');

    if (!els.form || !els.primaryBtn || !els.stakeInput || reels.some(function (r) { return !r; })) return;

    const SPIN_SYMBOLS = ['🍒', '🍋', '🔔', '⭐'];
    const SPIN_INTERVAL_MS = 60;
    const SPIN_DURATION_MS = 800;

    // Anti-autoclicker telemetry: same shape as coinflip/dice — capture
    // the bet button click coords + a doc-level mousemove flag so the
    // backend can score the player's pattern.
    const botSuspicion = window.TobyCasinoBotSuspicion &&
        window.TobyCasinoBotSuspicion.createTracker();
    if (botSuspicion) {
        document.addEventListener('mousemove', botSuspicion.recordMouseMove, { passive: true });
        [els.primaryBtn, els.tobyBtn].forEach(function (btn) {
            if (!btn) return;
            btn.addEventListener('click', botSuspicion.recordClick, true);
        });
    }

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
        // audible — like a slot machine's individual reel ticks. The
        // win/lose cue + chip flourish are fired by the shared
        // win-settle helper a beat after these clicks land.
        reels.forEach(function (reel, i) {
            reel.classList.remove('spinning');
            if (finalSymbols && finalSymbols[i]) reel.textContent = finalSymbols[i];
            if (window.CasinoSounds) {
                setTimeout(function () { window.CasinoSounds.play('click'); }, i * 110);
            }
        });
    }

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/slots/spin',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        minSettleMs: SPIN_DURATION_MS,
        failureMessage: 'Spin failed.',
        buildPayload: function (state) {
            const signals = botSuspicion ? botSuspicion.snapshotAndReset() : {
                clickX: null, clickY: null, mouseMoved: null,
            };
            return {
                stake: state.stake,
                autoTopUp: state.autoTopUp,
                clickX: signals.clickX,
                clickY: signals.clickY,
                mouseMoved: signals.mouseMoved,
            };
        },
        startAnimation: startSpinAnimation,
        stopAnimation: stopSpinAnimation,
        renderResult: function (body) { renderSlotsResult(els.resultEl, body, reels); },
        flashTarget: machineEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderSlotsResult };
}
