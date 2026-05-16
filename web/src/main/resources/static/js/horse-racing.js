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
    // Maximum time the slowest horse needs to traverse the rail after
    // stopAnimation kicks off the rank-staggered finish. Used both as
    // the result-line reveal delay and the renderResult Promise
    // resolve gate.
    const MAX_FINISH_MS = RACE_MS + (FIELD_SIZE - 1) * STAGGER_MS;

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
            r.style.left = '0';
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
        // Scroll the track into view on mobile so the user actually
        // sees the race after tapping Race — the bet form lives below
        // the track and the action can otherwise happen off-screen.
        if (track && typeof track.scrollIntoView === 'function') {
            track.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
        // Visible warm-up while we wait for the server. Each horse heads
        // toward a per-lane target between ~30 % and ~50 % of the rail
        // over 4–7 s — long enough that they're still mid-stride when a
        // slow `autoTopUp` response (1–3 s) finally lands, and jittered
        // per lane so they don't move in lockstep. `stopAnimation` then
        // overrides each horse's transition to land at the finish at its
        // rank-staggered time, continuing smoothly from the warm-up
        // position. Without this the rail looked frozen for the full
        // request + minSettleMs gate (~5 s in the worst case).
        runners.forEach(function (r) {
            if (!r) return;
            const targetPct = 30 + Math.random() * 20;
            const duration = 4000 + Math.random() * 3000;
            r.style.transition = 'left ' + duration + 'ms cubic-bezier(0.45, 0.05, 0.55, 0.95)';
            r.style.left = 'calc(' + targetPct.toFixed(1) + '% - 1.6rem)';
        });
        return null;
    }

    function stopAnimation(_intervalId, body) {
        clearAnimationTimers();
        if (!body || !body.finishingOrder || body.finishingOrder.length !== FIELD_SIZE) {
            return;
        }
        // Redirect every horse to the finish line in a single transition
        // with a rank-specific duration: winner (rank 0) takes RACE_MS,
        // last (rank FIELD_SIZE-1) takes RACE_MS + 5×STAGGER_MS. The
        // "snapshot the current computed left, reset transition to none,
        // force reflow, then set the new transition + target" dance is
        // the standard CSS-transition-restart pattern — without it the
        // browser would keep running the warm-up transition because
        // changing transition-duration alone doesn't reset the
        // animation. With it, each horse continues smoothly from its
        // current warm-up position to the finish over the new duration.
        body.finishingOrder.forEach(function (horseIdx, rank) {
            const runner = runners[horseIdx - 1];
            if (!runner) return;
            const currentLeft = window.getComputedStyle(runner).left;
            runner.style.transition = 'none';
            runner.style.left = currentLeft;
            // Force reflow so the new starting position takes hold
            // before we apply the next transition.
            // eslint-disable-next-line no-unused-expressions
            runner.offsetWidth;
            const duration = RACE_MS + rank * STAGGER_MS;
            runner.style.transition = 'left ' + duration + 'ms cubic-bezier(0.35, 0.1, 0.45, 1)';
            runner.style.left = 'calc(100% - 2rem)';
            const timer = setTimeout(function () {
                runner.classList.add('hr-runner-finished');
            }, duration);
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
        // Fire `stopAnimation` immediately on response so the rail
        // redirects from warm-up to the staggered finish as soon as we
        // know the order. The race-length gate that used to live here
        // moved into `renderResult` below, which returns a Promise that
        // resolves after the slowest horse crosses — keeping the result
        // line, balance update, and button re-enable held until then.
        minSettleMs: 0,
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
        renderResult: function (body) {
            // The Promise gate ensures the result line, the win-settle
            // chip flourish, the balance update, and the button
            // re-enable all wait for the slowest horse to cross the
            // line — `casino-game.js`'s `finishSettle` only runs after
            // this resolves. Painting the result line at t=0 would
            // spoil the reveal (the podium is right there); delaying
            // it until MAX_FINISH_MS lets the player watch the race
            // resolve first and then see "🥇 H3 · +220 credits".
            return new Promise(function (resolve) {
                setTimeout(function () {
                    renderHorseRacingResult(els.resultEl, body);
                    resolve();
                }, MAX_FINISH_MS);
            });
        },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderHorseRacingResult };
}
