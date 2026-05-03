// Pure-DOM render for a /roll response. Hoisted out of the IIFE so the
// jest test in `dice.test.js` can drive it without booting the page.
function renderDiceResult(resultEl, body, flashTargetEl) {
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
    // Same chip flourish blackjack/poker use on a winning hand.
    if (typeof window !== 'undefined' && window.CasinoRender) {
        window.CasinoRender.flashWinPayout(flashTargetEl, body);
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
        if (body && window.CasinoSounds) {
            window.CasinoSounds.play('click');
            setTimeout(function () {
                window.CasinoSounds.play(body.net > 0 ? 'win' : 'lose');
            }, 80);
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
            return {
                prediction: selectedPrediction(),
                stake: state.stake,
                autoTopUp: state.autoTopUp,
            };
        },
        startAnimation: startRollAnimation,
        stopAnimation: stopRollAnimation,
        renderResult: function (body) { renderDiceResult(els.resultEl, body, tableEl); },
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderDiceResult };
}
