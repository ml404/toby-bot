// Tracker for autoclicker-suspicion signals (click coords + mousemove
// flag). Hoisted out of the page IIFE as a factory so jest can exercise
// it without setting up the full DOM. Each call to `snapshotAndReset()`
// returns the pending values and clears `mouseMoved` for the next bet.
function createBotSuspicionTracker() {
    let mouseMoved = false;
    let lastClickX = null;
    let lastClickY = null;
    return {
        recordMouseMove: function () { mouseMoved = true; },
        recordClick: function (event) {
            if (!event) return;
            lastClickX = typeof event.clientX === 'number' ? event.clientX : null;
            lastClickY = typeof event.clientY === 'number' ? event.clientY : null;
        },
        snapshotAndReset: function () {
            const snapshot = {
                clickX: lastClickX,
                clickY: lastClickY,
                mouseMoved: mouseMoved,
            };
            // Frontend reports motion *between* bets, not since page load.
            // Coords stay set so the next click overwrites naturally; if
            // none arrives (e.g. keyboard submit) the prior coords are
            // resent, but `mouseMoved=false` plus a backend epsilon check
            // would still increment streak — for that path we want a true
            // null, so callers should clear coords explicitly when no
            // click event landed. Today every submit is preceded by a
            // click on the bet button, so this is theoretical.
            mouseMoved = false;
            return snapshot;
        },
    };
}

// Pure-DOM render for a /flip response. Hoisted out of the IIFE so the
// jest test in `coinflip.test.js` can drive it without booting the page.
function renderCoinflipResult(resultEl, body, flashTargetEl) {
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
    // Same chip flourish blackjack uses when a hand pays out.
    if (typeof window !== 'undefined' && window.CasinoRender) {
        window.CasinoRender.flashWinPayout(flashTargetEl, body);
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

    // Bot-suspicion telemetry: capture (clickX, clickY) from the bet button's
    // click event and `mouseMoved` from a document-level mousemove watcher.
    // The backend (`CoinflipBotSuspicionService`) compares consecutive clicks
    // — same pixel + no movement = autoclicker, ramps house edge. Genuine
    // play moves the cursor at least a few pixels between bets, which the
    // mousemove listener catches.
    const botSuspicion = createBotSuspicionTracker();
    document.addEventListener('mousemove', botSuspicion.recordMouseMove, { passive: true });
    [els.primaryBtn, els.tobyBtn].forEach(function (btn) {
        if (!btn) return;
        // Capture phase so we read coords before the form's submit listener
        // runs the buildPayload pipeline.
        btn.addEventListener('click', botSuspicion.recordClick, true);
    });

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
        if (body && window.CasinoSounds) {
            window.CasinoSounds.play('chip');
            setTimeout(function () {
                window.CasinoSounds.play(body.net > 0 ? 'win' : 'lose');
            }, 80);
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
            const signals = botSuspicion.snapshotAndReset();
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
        renderResult: function (body) { renderCoinflipResult(els.resultEl, body, tableEl); },
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderCoinflipResult, createBotSuspicionTracker };
}
