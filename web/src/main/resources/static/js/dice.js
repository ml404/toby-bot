// Pure-DOM render for a /roll response. Hoisted out of the IIFE so the
// jest test in `dice.test.js` can drive it without booting the page.
//
// Win/lose sound + chip flourish are owned by the shared
// `casino-win-settle.js` helper, fired by casino-game.js after this
// renderResult returns (see flashTarget in init below).
function renderDiceResult(resultEl, body) {
    if (typeof window !== 'undefined' && window.TobyCasinoResult) {
        window.TobyCasinoResult.render({
            resultEl: resultEl,
            body: body,
            classPrefix: 'dice',
            winLineHtml: '<strong>' + body.landed + '!</strong> You called ' +
                body.predicted + ' &middot; <strong>+' + body.net + ' credits</strong>',
            loseLineHtml: '<strong>' + body.landed + '.</strong> You called ' +
                body.predicted + ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>',
        });
    }
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('dice', 'roll');
    if (!els) return;

    const die = document.getElementById('dice-die');
    const dieFace = document.getElementById('dice-die-face');
    const tableEl = document.querySelector('.dice-table');

    if (!els.form || !els.primaryBtn || !els.stakeInput || !die || !dieFace) return;

    const FACES = { 1: '⚀', 2: '⚁', 3: '⚂', 4: '⚃', 5: '⚄', 6: '⚅' };
    const ROLL_DURATION_MS = 800;
    const ROLL_INTERVAL_MS = 70;

    // Anti-autoclicker telemetry: same shape as coinflip/slots — capture
    // (clickX, clickY) from the bet button + a doc-level mousemove flag,
    // forward both to the backend so CasinoBotSuspicionService can score
    // the player's pattern. Genuine cursor motion clears the streak.
    const botSuspicion = window.TobyCasinoBotSuspicion &&
        window.TobyCasinoBotSuspicion.createTracker();
    if (botSuspicion) {
        document.addEventListener('mousemove', botSuspicion.recordMouseMove, { passive: true });
        [els.primaryBtn, els.tobyBtn].forEach(function (btn) {
            if (!btn) return;
            btn.addEventListener('click', botSuspicion.recordClick, true);
        });
    }

    function selectedPrediction() {
        const checked = els.form.querySelector('input[name="prediction"]:checked');
        return checked ? parseInt(checked.value, 10) : null;
    }

    function startRollAnimation() {
        die.classList.add('rolling');
        if (window.CasinoSounds) window.CasinoSounds.play('deal');
        return setInterval(function () {
            const n = 1 + Math.floor(Math.random() * 6);
            dieFace.textContent = FACES[n] || String(n);
        }, ROLL_INTERVAL_MS);
    }

    function stopRollAnimation(intervalId, body) {
        clearInterval(intervalId);
        die.classList.remove('rolling');
        const landed = body && body.landed;
        if (landed && FACES[landed]) {
            dieFace.textContent = FACES[landed];
            die.dataset.landed = String(landed);
        } else {
            dieFace.textContent = '⚀';
            delete die.dataset.landed;
        }
        // Click on landing — the win/lose cue follows from the shared
        // win-settle helper a beat later.
        if (body && window.CasinoSounds) {
            window.CasinoSounds.play('click');
        }
    }

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/dice/roll',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        minSettleMs: ROLL_DURATION_MS,
        failureMessage: 'Roll failed.',
        validate: function () {
            if (!selectedPrediction()) return 'Pick a number first.';
            return null;
        },
        buildPayload: function (state) {
            const signals = botSuspicion ? botSuspicion.snapshotAndReset() : {
                clickX: null, clickY: null, mouseMoved: null,
            };
            return {
                prediction: selectedPrediction(),
                stake: state.stake,
                autoTopUp: state.autoTopUp,
                clickX: signals.clickX,
                clickY: signals.clickY,
                mouseMoved: signals.mouseMoved,
            };
        },
        startAnimation: startRollAnimation,
        stopAnimation: stopRollAnimation,
        renderResult: function (body) { renderDiceResult(els.resultEl, body); },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderDiceResult };
}
