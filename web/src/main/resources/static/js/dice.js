// Pure-DOM render for a /roll response. Hoisted out of the IIFE so the
// jest test in `dice.test.js` can drive it without booting the page.
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

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const die = document.getElementById('dice-die');
    const dieFace = document.getElementById('dice-die-face');
    const stakeInput = document.getElementById('dice-stake');
    const rollBtn = document.getElementById('dice-roll');
    const rollTobyBtn = document.getElementById('dice-roll-toby');
    const balanceEl = document.getElementById('dice-balance');
    const resultEl = document.getElementById('dice-result');
    const form = document.getElementById('dice-bet');

    if (!form || !rollBtn || !stakeInput || !die || !dieFace) return;

    const FACES = { 1: '⚀', 2: '⚁', 3: '⚂', 4: '⚃', 5: '⚄', 6: '⚅' };
    const ROLL_DURATION_MS = 800;
    const ROLL_INTERVAL_MS = 70;

    function selectedPrediction() {
        const checked = form.querySelector('input[name="prediction"]:checked');
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
        guildId: guildId,
        endpoint: '/casino/' + guildId + '/dice/roll',
        form: form,
        stakeInput: stakeInput,
        primaryBtn: rollBtn,
        tobyBtn: rollTobyBtn,
        balanceEl: balanceEl,
        resultEl: resultEl,
        tobyCoins: Number(main.dataset.tobyCoins) || 0,
        marketPrice: Number(main.dataset.marketPrice) || 0,
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
        renderResult: function (body) { renderDiceResult(resultEl, body); },
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderDiceResult };
}
