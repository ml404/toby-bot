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
    const initialTobyCoins = Number(main.dataset.tobyCoins) || 0;
    const initialMarketPrice = Number(main.dataset.marketPrice) || 0;
    const postJson = window.TobyApi && window.TobyApi.postJson;

    const coin = document.getElementById('coinflip-coin');
    const coinFace = document.getElementById('coinflip-coin-face');
    const stakeInput = document.getElementById('coinflip-stake');
    const flipBtn = document.getElementById('coinflip-flip');
    const flipTobyBtn = document.getElementById('coinflip-flip-toby');
    const balanceEl = document.getElementById('coinflip-balance');
    const resultEl = document.getElementById('coinflip-result');
    const form = document.getElementById('coinflip-bet');

    if (!form || !flipBtn || !stakeInput || !coin || !coinFace) return;

    const topUp = (window.TobyTopUp && flipTobyBtn) ? window.TobyTopUp.init({
        form: form,
        stakeInput: stakeInput,
        primaryBtn: flipBtn,
        tobyBtn: flipTobyBtn,
        balanceEl: balanceEl,
        tobyCoins: initialTobyCoins,
        marketPrice: initialMarketPrice,
        onSubmit: function (autoTopUp) { runFlip(autoTopUp); },
    }) : null;

    const FLIP_DURATION_MS = 800;
    const FLIP_INTERVAL_MS = 80;
    // Faces shown during the flip animation. Final face comes from the
    // server response.
    const FLIP_FACES = ['🪙', '🟡'];

    let flipping = false;

    function selectedSide() {
        const checked = form.querySelector('input[name="side"]:checked');
        return checked ? checked.value : null;
    }

    function startFlipAnimation() {
        flipping = true;
        coin.classList.add('flipping');
        let i = 0;
        return setInterval(function () {
            coinFace.textContent = FLIP_FACES[i % FLIP_FACES.length];
            i++;
        }, FLIP_INTERVAL_MS);
    }

    function stopFlipAnimation(intervalId, landedSide) {
        clearInterval(intervalId);
        flipping = false;
        coin.classList.remove('flipping');
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
    }

    function applyBalance(newBalance) {
        if (typeof newBalance !== 'number') return;
        if (balanceEl) balanceEl.textContent = newBalance;
    }

    function applyTobyDelta(body) {
        if (!topUp) return;
        if (typeof body.soldTobyCoins === 'number') {
            const remaining = Math.max(0, initialTobyCoins - body.soldTobyCoins);
            topUp.setTobyCoins(remaining);
        }
        if (typeof body.newPrice === 'number') topUp.setMarketPrice(body.newPrice);
    }

    function runFlip(autoTopUp) {
        if (flipping) return;

        const side = selectedSide();
        if (!side) {
            toast('Pick a side first.', 'error');
            return;
        }
        const stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            toast('Enter a positive stake.', 'error');
            return;
        }

        flipBtn.disabled = true;
        if (flipTobyBtn) flipTobyBtn.disabled = true;
        const intervalId = startFlipAnimation();
        const requestStart = Date.now();

        if (!postJson) {
            stopFlipAnimation(intervalId);
            flipBtn.disabled = false;
            if (flipTobyBtn) flipTobyBtn.disabled = false;
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        postJson('/casino/' + guildId + '/coinflip/flip', {
            side: side, stake: stake, autoTopUp: !!autoTopUp,
        })
            .then(function (body) {
                const elapsed = Date.now() - requestStart;
                const remaining = Math.max(0, FLIP_DURATION_MS - elapsed);
                setTimeout(function () {
                    stopFlipAnimation(intervalId, body && body.landed);
                    flipBtn.disabled = false;
                    if (flipTobyBtn) flipTobyBtn.disabled = false;
                    if (body && body.ok) {
                        renderCoinflipResult(resultEl, body);
                        applyBalance(body.newBalance);
                        applyTobyDelta(body);
                    } else {
                        if (resultEl) resultEl.hidden = true;
                        toast((body && body.error) || 'Flip failed.', 'error');
                    }
                }, remaining);
            })
            .catch(function () {
                stopFlipAnimation(intervalId);
                flipBtn.disabled = false;
                if (flipTobyBtn) flipTobyBtn.disabled = false;
                if (resultEl) resultEl.hidden = true;
                toast('Network error.', 'error');
            });
    }

    if (!topUp) {
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            runFlip(false);
        });
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderCoinflipResult };
}
