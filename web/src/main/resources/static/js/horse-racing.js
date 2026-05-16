// Horse Racing renderer + animator.
//
// Six lanes; each lane carries one horse emoji that advances from left
// (start gate) to right (finish flag) over the settle window. The
// server returns the finishing order in `body.finishingOrder` — we
// compute per-horse arrival times so the runners cross the line in
// that order, regardless of how the random jitter plays out during
// the race.
//
// `renderHorseRacingResult` is hoisted so any jest test can drive it
// without booting the lanes.

function renderHorseRacingResult(resultEl, body) {
    if (typeof window === 'undefined' || !window.TobyCasinoResult) return;
    const podium = (body && body.finishingOrder && body.finishingOrder.length)
        ? body.finishingOrder.slice(0, 3).map(function (h, i) {
            const medal = ['🥇', '🥈', '🥉'][i] || (i + 1) + '.';
            return medal + ' H' + h;
        }).join(' · ')
        : '?';
    const mult = (body && typeof body.multiplier === 'number')
        ? body.multiplier.toFixed(2).replace(/\.?0+$/, '') + '×'
        : '?';
    const pickedLine = (body && body.pickedHorse)
        ? '<span>You backed H' + body.pickedHorse + ' to ' + (body.betLabel || body.bet || 'finish') + '.</span>'
        : '';
    const winLine = '<strong>' + podium + '</strong> &middot; ' +
        '<strong>+' + (body && body.net != null ? body.net : 0) + ' credits</strong> ' +
        '(' + mult + ')' +
        (pickedLine ? '<br>' + pickedLine : '');
    const loseLine = '<strong>' + podium + '</strong> &middot; ' +
        'lost <strong>' + (body && body.net != null ? Math.abs(body.net) : 0) + ' credits</strong>' +
        (pickedLine ? '<br>' + pickedLine : '');
    window.TobyCasinoResult.render({
        resultEl: resultEl,
        body: body,
        classPrefix: 'hr',
        winLineHtml: winLine,
        loseLineHtml: loseLine,
    });
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('hr', 'go');
    if (!els) return;

    const track = document.getElementById('hr-track');
    const tableEl = document.querySelector('.hr-table');
    if (!els.form || !els.primaryBtn || !els.stakeInput || !track) return;

    const FIELD_SIZE = Number(els.main.dataset.fieldSize) || 6;
    const RACE_MS = 2600;
    const STAGGER_MS = 220;

    const lanes = Array.from(track.querySelectorAll('.hr-lane'));
    const runners = lanes.map(function (lane) {
        return lane.querySelector('.hr-runner');
    });

    // Anti-autoclicker telemetry — same shape as other casino-game JS.
    const botSuspicion = window.TobyCasinoBotSuspicion &&
        window.TobyCasinoBotSuspicion.createTracker();
    if (botSuspicion) {
        document.addEventListener('mousemove', botSuspicion.recordMouseMove, { passive: true });
        [els.primaryBtn, els.tobyBtn].forEach(function (btn) {
            if (!btn) return;
            btn.addEventListener('click', botSuspicion.recordClick, true);
        });
    }

    function selectedHorse() {
        const checked = els.form.querySelector('input[name="horse"]:checked');
        return checked ? parseInt(checked.value, 10) : null;
    }

    function selectedBet() {
        const checked = els.form.querySelector('input[name="bet"]:checked');
        return checked ? checked.value : null;
    }

    function resetLanes() {
        runners.forEach(function (r) {
            if (!r) return;
            r.style.transition = 'none';
            r.style.transform = 'translateX(0%)';
            r.classList.remove('hr-runner-finished');
        });
        // Force reflow so the next transition takes effect.
        // eslint-disable-next-line no-unused-expressions
        track.offsetWidth;
    }

    let animationTimers = [];
    function clearAnimationTimers() {
        animationTimers.forEach(function (id) { clearTimeout(id); });
        animationTimers = [];
    }

    function startAnimation() {
        resetLanes();
        if (window.CasinoSounds) window.CasinoSounds.play('deal');
        // Kick every horse off with a slow trot toward the finish — they
        // settle to their actual finishing spots once the server reply
        // arrives. If the server is slow we just keep advancing slowly
        // so the lanes are visibly active.
        runners.forEach(function (r, i) {
            if (!r) return;
            r.style.transition = 'transform ' + RACE_MS + 'ms cubic-bezier(0.45, 0.05, 0.55, 0.95)';
            r.style.transform = 'translateX(60%)';
        });
        return null;
    }

    function stopAnimation(_intervalId, body) {
        clearAnimationTimers();
        if (!body || !body.finishingOrder || body.finishingOrder.length !== FIELD_SIZE) {
            return;
        }
        // Assign each horse a finishing tick based on its rank. The
        // winner crosses first (RACE_MS), each subsequent place is
        // STAGGER_MS later, so the visual finish order matches the
        // server result regardless of the jittered run-up.
        body.finishingOrder.forEach(function (horseIdx, rank) {
            const runner = runners[horseIdx - 1];
            if (!runner) return;
            const delay = rank * STAGGER_MS;
            const timer = setTimeout(function () {
                runner.style.transition = 'transform 500ms cubic-bezier(0.2, 0.7, 0.3, 1)';
                runner.style.transform = 'translateX(100%)';
                runner.classList.add('hr-runner-finished');
            }, delay);
            animationTimers.push(timer);
        });
        if (window.CasinoSounds) {
            setTimeout(function () { window.CasinoSounds.play('click'); }, RACE_MS);
        }
    }

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/horse-racing/race',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        // Wait long enough for the lanes to visibly settle before
        // surfacing the win/lose copy. RACE_MS + last-place stagger.
        minSettleMs: RACE_MS + (FIELD_SIZE - 1) * STAGGER_MS + 400,
        failureMessage: 'Race failed.',
        validate: function () {
            if (!selectedHorse()) return 'Pick a horse first.';
            if (!selectedBet()) return 'Pick a bet type.';
            return null;
        },
        buildPayload: function (state) {
            const signals = botSuspicion ? botSuspicion.snapshotAndReset() : {
                clickX: null, clickY: null, mouseMoved: null,
            };
            return {
                horse: selectedHorse(),
                bet: selectedBet(),
                stake: state.stake,
                autoTopUp: state.autoTopUp,
                clickX: signals.clickX,
                clickY: signals.clickY,
                mouseMoved: signals.mouseMoved,
            };
        },
        startAnimation: startAnimation,
        stopAnimation: stopAnimation,
        renderResult: function (body) { renderHorseRacingResult(els.resultEl, body); },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderHorseRacingResult };
}
