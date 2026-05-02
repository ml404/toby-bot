// Pure-DOM render for a /flip response. Hoisted out of the IIFE so the
// jest test in `coinflip.test.js` can drive it without booting the page.
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

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const coin = document.getElementById('coinflip-coin');
    const coinFace = document.getElementById('coinflip-coin-face');
    const stakeInput = document.getElementById('coinflip-stake');
    const flipBtn = document.getElementById('coinflip-flip');
    const flipTobyBtn = document.getElementById('coinflip-flip-toby');
    const balanceEl = document.getElementById('coinflip-balance');
    const resultEl = document.getElementById('coinflip-result');
    const form = document.getElementById('coinflip-bet');

    if (!form || !flipBtn || !stakeInput || !coin || !coinFace) return;

    const FLIP_DURATION_MS = 800;
    const FLIP_INTERVAL_MS = 80;
    // Faces shown during the flip animation. Final face comes from the server.
    const FLIP_FACES = ['🪙', '🟡'];

    function selectedSide() {
        const checked = form.querySelector('input[name="side"]:checked');
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
        guildId: guildId,
        endpoint: '/casino/' + guildId + '/coinflip/flip',
        form: form,
        stakeInput: stakeInput,
        primaryBtn: flipBtn,
        tobyBtn: flipTobyBtn,
        balanceEl: balanceEl,
        resultEl: resultEl,
        tobyCoins: Number(main.dataset.tobyCoins) || 0,
        marketPrice: Number(main.dataset.marketPrice) || 0,
        minSettleMs: FLIP_DURATION_MS,
        failureMessage: 'Flip failed.',
        validate: function () {
            if (!selectedSide()) return 'Pick a side first.';
            return null;
        },
        buildPayload: function (state) {
            return { side: selectedSide(), stake: state.stake, autoTopUp: state.autoTopUp };
        },
        startAnimation: startFlipAnimation,
        stopAnimation: stopFlipAnimation,
        renderResult: function (body) { renderCoinflipResult(resultEl, body); },
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderCoinflipResult };
}
