// Pure-DOM render for a /spin response. Hoisted out of the IIFE for tests.
function renderWheelResult(resultEl, body) {
    if (typeof window === 'undefined' || !window.TobyCasinoResult) return;
    const landed = body && body.landed != null ? body.landed + '×' : '?';
    const pick = body && body.pick != null ? body.pick + '×' : '?';
    const winLine = '<strong>' + landed + '</strong> &middot; you picked ' + pick +
        ' &middot; <strong>+' + (body && typeof body.net === 'number' ? body.net : 0) + ' credits</strong>';
    const loseLine = '<strong>' + landed + '</strong> &middot; you picked ' + pick +
        ' &middot; lost <strong>' +
        (body && typeof body.net === 'number' ? Math.abs(body.net) : 0) + ' credits</strong>';
    window.TobyCasinoResult.render({
        resultEl: resultEl,
        body: body,
        classPrefix: 'wheel',
        winLineHtml: winLine,
        loseLineHtml: loseLine,
    });
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('wheel', 'spin');
    if (!els) return;

    const disc = document.getElementById('wheel-disc');
    const landedEl = document.getElementById('wheel-landed');
    const tableEl = document.querySelector('.wheel-table');
    if (!els.form || !els.primaryBtn || !els.stakeInput || !disc || !landedEl) return;

    const SPIN_DURATION_MS = 900;
    const SPIN_TICK_MS = 70;
    const FACES = ['2×', '3×', '5×', '10×'];

    // Anti-autoclicker telemetry: same shape as dice/coinflip/slots.
    const botSuspicion = window.TobyCasinoBotSuspicion &&
        window.TobyCasinoBotSuspicion.createTracker();
    if (botSuspicion) {
        document.addEventListener('mousemove', botSuspicion.recordMouseMove, { passive: true });
        [els.primaryBtn, els.tobyBtn].forEach(function (btn) {
            if (!btn) return;
            btn.addEventListener('click', botSuspicion.recordClick, true);
        });
    }

    function selectedPick() {
        const checked = els.form.querySelector('input[name="pick"]:checked');
        return checked ? parseInt(checked.value, 10) : null;
    }

    function startSpinAnimation() {
        disc.classList.add('spinning');
        if (window.CasinoSounds) window.CasinoSounds.play('deal');
        return setInterval(function () {
            const f = FACES[Math.floor(Math.random() * FACES.length)];
            landedEl.textContent = f;
        }, SPIN_TICK_MS);
    }

    function stopSpinAnimation(intervalId, body) {
        clearInterval(intervalId);
        disc.classList.remove('spinning');
        if (body && body.landed != null) {
            landedEl.textContent = body.landed + '×';
            disc.dataset.landed = String(body.landed);
        } else {
            landedEl.textContent = '?';
            delete disc.dataset.landed;
        }
        if (body && window.CasinoSounds) window.CasinoSounds.play('click');
    }

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/wheel/spin',
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
        validate: function () {
            if (!selectedPick()) return 'Pick a multiplier first.';
            return null;
        },
        buildPayload: function (state) {
            const signals = botSuspicion ? botSuspicion.snapshotAndReset() : {
                clickX: null, clickY: null, mouseMoved: null,
            };
            return {
                stake: state.stake,
                pick: selectedPick(),
                autoTopUp: state.autoTopUp,
                clickX: signals.clickX,
                clickY: signals.clickY,
                mouseMoved: signals.mouseMoved,
            };
        },
        startAnimation: startSpinAnimation,
        stopAnimation: stopSpinAnimation,
        renderResult: function (body) { renderWheelResult(els.resultEl, body); },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderWheelResult };
}
