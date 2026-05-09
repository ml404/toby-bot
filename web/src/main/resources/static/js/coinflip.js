// Pure-DOM render for a /flip response. Hoisted out of the IIFE so the
// jest test in `coinflip.test.js` can drive it without booting the page.
//
// Win/lose sound + chip flourish are owned by the shared
// `casino-win-settle.js` helper, fired by casino-game.js after this
// renderResult returns (see flashTarget in init below).
function renderCoinflipResult(resultEl, body) {
    const landedLabel = body.landed === 'HEADS' ? 'Heads' : 'Tails';
    const predictedLabel = body.predicted === 'HEADS' ? 'Heads' : 'Tails';
    if (typeof window !== 'undefined' && window.TobyCasinoResult) {
        window.TobyCasinoResult.render({
            resultEl: resultEl,
            body: body,
            classPrefix: 'coinflip',
            winLineHtml: '<strong>' + landedLabel + '!</strong> You called ' +
                predictedLabel + ' &middot; <strong>+' + body.net + ' credits</strong>',
            loseLineHtml: '<strong>' + landedLabel + '.</strong> You called ' +
                predictedLabel + ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>',
        });
    }
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('coinflip', 'flip');
    if (!els) return;

    const coin = document.getElementById('coinflip-coin');
    const coinFace = document.getElementById('coinflip-coin-face');
    const tableEl = document.querySelector('.coinflip-table');

    if (!els.form || !els.primaryBtn || !els.stakeInput || !coin || !coinFace) return;

    const FLIP_DURATION_MS = 800;
    const FLIP_INTERVAL_MS = 80;
    // Faces shown during the flip animation. Final face comes from the server.
    const FLIP_FACES = ['🪙', '🟡'];

    // Anti-autoclicker telemetry: capture (clickX, clickY) from the bet
    // button + a doc-level mousemove flag. Backend's CasinoBotSuspicionService
    // scores the streak; CasinoEdgeService converts it into a forced-loss
    // probability. Genuine cursor motion clears the streak.
    const botSuspicion = window.TobyCasinoBotSuspicion &&
        window.TobyCasinoBotSuspicion.createTracker();
    if (botSuspicion) {
        document.addEventListener('mousemove', botSuspicion.recordMouseMove, { passive: true });
        [els.primaryBtn, els.tobyBtn].forEach(function (btn) {
            if (!btn) return;
            // Capture phase so we read coords before the form's submit listener
            // runs the buildPayload pipeline.
            btn.addEventListener('click', botSuspicion.recordClick, true);
        });
    }

    function selectedSide() {
        const checked = els.form.querySelector('input[name="side"]:checked');
        return checked ? checked.value : null;
    }

    function startFlipAnimation() {
        coin.classList.add('flipping');
        if (window.CasinoSounds) window.CasinoSounds.play('flip');
        let i = 0;
        return setInterval(function () {
            coinFace.textContent = FLIP_FACES[i % FLIP_FACES.length];
            i++;
        }, FLIP_INTERVAL_MS);
    }

    function stopFlipAnimation(intervalId, body) {
        clearInterval(intervalId);
        coin.classList.remove('flipping');
        const landedSide = body && body.landed;
        if (landedSide === 'HEADS') {
            coinFace.textContent = 'H';
            coin.dataset.landed = 'heads';
        } else if (landedSide === 'TAILS') {
            coinFace.textContent = 'T';
            coin.dataset.landed = 'tails';
        } else {
            coinFace.textContent = '🪙';
            delete coin.dataset.landed;
        }
        // Chip-clink on landing — the win/lose cue follows from the
        // shared win-settle helper a beat later.
        if (body && window.CasinoSounds) {
            window.CasinoSounds.play('chip');
        }
    }

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/coinflip/flip',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        minSettleMs: FLIP_DURATION_MS,
        failureMessage: 'Flip failed.',
        validate: function () {
            if (!selectedSide()) return 'Pick a side first.';
            return null;
        },
        buildPayload: function (state) {
            const signals = botSuspicion ? botSuspicion.snapshotAndReset() : {
                clickX: null, clickY: null, mouseMoved: null,
            };
            return {
                side: selectedSide(),
                stake: state.stake,
                autoTopUp: state.autoTopUp,
                clickX: signals.clickX,
                clickY: signals.clickY,
                mouseMoved: signals.mouseMoved,
            };
        },
        startAnimation: startFlipAnimation,
        stopAnimation: stopFlipAnimation,
        renderResult: function (body) { renderCoinflipResult(els.resultEl, body); },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderCoinflipResult };
}
