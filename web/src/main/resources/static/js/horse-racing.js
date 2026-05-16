// Horse Racing renderer + animator.
//
// Six lanes; each lane carries one horse emoji that advances from left
// (start gate) to right (finish flag) over the settle window. During
// the pending phase (waiting for the server to return the finishing
// order) each runner heads toward a jittered mid-rail target so the
// rail looks alive; once the response lands, every runner is redirected
// to the finish flag with a rank-specific duration so they cross the
// line in the server-returned order.
//
// Both `renderHorseRacingResult` and `createHorseRacingAnimator` are
// hoisted exports so the jest tests can drive the result-line and the
// lane animations without booting the IIFE.

const HR_RACE_MS = 2600;
const HR_STAGGER_MS = 220;
const HR_FIELD_SIZE = 6;
// Maximum time the slowest horse needs to traverse the rail after
// stopAnimation kicks off the rank-staggered finish. Used both as the
// result-line reveal delay and the renderResult Promise resolve gate.
const HR_MAX_FINISH_MS = HR_RACE_MS + (HR_FIELD_SIZE - 1) * HR_STAGGER_MS;

/**
 * Repaints the per-horse odds label inside each `.hr-horse` card so it
 * shows the multiplier for the currently-selected bet type. Each horse
 * card carries all three multipliers on `data-{win|place|show}-mult`
 * attributes (server-rendered from `HorseRacing.HORSES`), so the swap
 * is purely client-side — no fetch round-trip on bet-radio change.
 *
 * Hoisted so the jest test can drive it without booting the IIFE.
 */
function updateHorseOdds(horseCards, betType) {
    if (!horseCards || !horseCards.length) return;
    // Normalise the bet type. Anything outside the known set falls back
    // to WIN so a future server-side bet type added before the
    // frontend catches up doesn't leave blank labels on the cards.
    const raw = (typeof betType === 'string' ? betType : '').toLowerCase();
    const known = raw === 'win' || raw === 'place' || raw === 'show';
    const keySuffix = known ? raw : 'win';
    // Walk every horse card and rewrite its odds label. Cards without
    // a matching data attribute fall back to "?" rather than throwing —
    // a defensive guard for tests + any future bet types added without
    // updating the template.
    horseCards.forEach(function (card) {
        const oddsEl = card.querySelector('.hr-horse-odds');
        if (!oddsEl) return;
        const mult = card.dataset[keySuffix + 'Mult'];
        const display = (mult != null && mult !== '')
            ? parseFloat(mult).toFixed(1) + '× ' + keySuffix
            : '? ' + keySuffix;
        oddsEl.textContent = display;
    });
}

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

/**
 * Factory for the start/stop/renderResult triple that drives the six
 * racing lanes. Hoisted out of the IIFE so jest can exercise the
 * animation behaviour with mock DOM elements and fake timers.
 *
 * opts:
 *   runners:        Array of six HTMLElement runners (one per lane).
 *   track:          The lane container (used for reflow + scrollIntoView).
 *   resultEl:       Where renderResult eventually paints the win/lose line.
 *   fieldSize:      Defaults to 6.
 *   raceMs:         Winner finishing duration. Defaults to HR_RACE_MS.
 *   staggerMs:      Per-rank delta. Defaults to HR_STAGGER_MS.
 *   sounds:         Optional CasinoSounds-shaped { play(name) } stub.
 *   rng:            Optional () => number in [0,1) — overridable for tests.
 */
function createHorseRacingAnimator(opts) {
    const runners = opts.runners || [];
    const track = opts.track;
    const resultEl = opts.resultEl;
    const fieldSize = opts.fieldSize || HR_FIELD_SIZE;
    const raceMs = opts.raceMs || HR_RACE_MS;
    const staggerMs = opts.staggerMs || HR_STAGGER_MS;
    const sounds = opts.sounds || null;
    const rng = opts.rng || Math.random;
    const maxFinishMs = raceMs + (fieldSize - 1) * staggerMs;

    let animationTimers = [];
    function clearAnimationTimers() {
        animationTimers.forEach(function (id) { clearTimeout(id); });
        animationTimers = [];
    }

    function resetLanes() {
        runners.forEach(function (r) {
            if (!r) return;
            r.style.transition = 'none';
            r.style.left = '0';
            r.classList.remove('hr-runner-finished');
        });
        // Force reflow so the next transition takes effect.
        if (track) {
            // eslint-disable-next-line no-unused-expressions
            track.offsetWidth;
        }
    }

    function startAnimation() {
        resetLanes();
        if (sounds && typeof sounds.play === 'function') sounds.play('deal');
        // Scroll the track into view on mobile so the user actually sees
        // the race after tapping Race — the bet form lives below the
        // track and the action can otherwise happen off-screen.
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
        // position.
        runners.forEach(function (r) {
            if (!r) return;
            const targetPct = 30 + rng() * 20;
            const duration = 4000 + rng() * 3000;
            r.style.transition = 'left ' + duration + 'ms cubic-bezier(0.45, 0.05, 0.55, 0.95)';
            r.style.left = 'calc(' + targetPct.toFixed(1) + '% - 1.6rem)';
        });
        return null;
    }

    function stopAnimation(_intervalId, body) {
        clearAnimationTimers();
        if (!body || !body.finishingOrder || body.finishingOrder.length !== fieldSize) {
            return;
        }
        // Redirect every horse to the finish line in a single transition
        // with a rank-specific duration: winner (rank 0) takes raceMs,
        // last (rank fieldSize-1) takes raceMs + (fieldSize-1)*staggerMs.
        // The "snapshot the current computed left, reset transition to
        // none, force reflow, then set the new transition + target"
        // dance is the standard CSS-transition-restart pattern —
        // without it the browser would keep running the warm-up
        // transition because changing transition-duration alone doesn't
        // reset the animation. With it, each horse continues smoothly
        // from its current warm-up position to the finish over the new
        // duration.
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
            const duration = raceMs + rank * staggerMs;
            runner.style.transition = 'left ' + duration + 'ms cubic-bezier(0.35, 0.1, 0.45, 1)';
            runner.style.left = 'calc(100% - 2rem)';
            const timer = setTimeout(function () {
                runner.classList.add('hr-runner-finished');
            }, duration);
            animationTimers.push(timer);
        });
        if (sounds && typeof sounds.play === 'function') {
            const soundTimer = setTimeout(function () { sounds.play('click'); }, raceMs);
            animationTimers.push(soundTimer);
        }
    }

    function renderResult(body) {
        // The Promise gate ensures the result line, the win-settle chip
        // flourish, the balance update, and the button re-enable all
        // wait for the slowest horse to cross the line —
        // `casino-game.js`'s `finishSettle` only runs after this
        // resolves. Painting the result line at t=0 would spoil the
        // reveal (the podium is right there); delaying it until
        // maxFinishMs lets the player watch the race resolve first and
        // then see "🥇 H3 · +220 credits".
        return new Promise(function (resolve) {
            setTimeout(function () {
                renderHorseRacingResult(resultEl, body);
                resolve();
            }, maxFinishMs);
        });
    }

    return {
        startAnimation: startAnimation,
        stopAnimation: stopAnimation,
        renderResult: renderResult,
        maxFinishMs: maxFinishMs,
    };
}

(function () {
    'use strict';

    if (typeof window === 'undefined') return;
    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('hr', 'go');
    if (!els) return;

    const track = document.getElementById('hr-track');
    const tableEl = document.querySelector('.hr-table');
    if (!els.form || !els.primaryBtn || !els.stakeInput || !track) return;

    const fieldSize = Number(els.main.dataset.fieldSize) || HR_FIELD_SIZE;

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

    // Keep the per-horse odds label in sync with the currently-selected
    // bet type. Without this the cards always showed `winMult` even when
    // the user picked Place or Show — misleading for a 1.7× Place payout
    // vs. a 3.1× Win payout on the same favourite.
    const horseCards = Array.from(els.form.querySelectorAll('.hr-horse'));
    function refreshHorseOdds() {
        updateHorseOdds(horseCards, selectedBet());
    }
    els.form.querySelectorAll('input[name="bet"]').forEach(function (radio) {
        radio.addEventListener('change', refreshHorseOdds);
    });
    // Prime on load in case the server-rendered "× win" labels don't
    // match the initially-checked bet (e.g. a future template change
    // defaults to Show).
    refreshHorseOdds();

    const animator = createHorseRacingAnimator({
        runners: runners,
        track: track,
        resultEl: els.resultEl,
        fieldSize: fieldSize,
        sounds: window.CasinoSounds || null,
    });

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
        // moved into `renderResult` (in the animator factory), which
        // returns a Promise that resolves after the slowest horse
        // crosses — keeping the result line, balance update, and button
        // re-enable held until then.
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
        startAnimation: animator.startAnimation,
        stopAnimation: animator.stopAnimation,
        renderResult: animator.renderResult,
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        renderHorseRacingResult: renderHorseRacingResult,
        createHorseRacingAnimator: createHorseRacingAnimator,
        updateHorseOdds: updateHorseOdds,
        HR_RACE_MS: HR_RACE_MS,
        HR_STAGGER_MS: HR_STAGGER_MS,
        HR_MAX_FINISH_MS: HR_MAX_FINISH_MS,
    };
}
