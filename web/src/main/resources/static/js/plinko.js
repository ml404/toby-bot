// Plinko renderer + animator.
//
// SVG board: pegs in a Galton triangle (row k has k+1 pegs), buckets
// along the bottom labelled with the active risk profile's multipliers.
// The ball is a separate SVG <circle> whose translate is driven by
// requestAnimationFrame — it bounces through each row's decision point
// and ends at the EXACT centre of the bucket the server picked.
//
// Risk-profile radio change re-paints the bucket labels and bucket
// tints in place (no page reload); the active profile's payout vector
// is read from window.__plinkoTables (server-rendered Thymeleaf blob).
//
// renderPlinkoResult stays as a hoisted export so the jest test in
// `plinko.test.js` can drive it without booting the SVG board.

function renderPlinkoResult(resultEl, body) {
    if (typeof window === 'undefined' || !window.TobyCasinoResult) return;
    const mult = (body && typeof body.multiplier === 'number')
        ? body.multiplier.toFixed(2).replace(/\.?0+$/, '') + '×'
        : '?';
    const bucket = (body && typeof body.bucket === 'number') ? body.bucket : '?';
    const isPush = !!(body && body.push);
    const winLine = '<strong>Bucket ' + bucket + ' &middot; ' + mult + '</strong>' +
        (body && typeof body.net === 'number'
            ? ' &middot; <strong>+' + body.net + ' credits</strong>'
            : '');
    let loseLine = '<strong>Bucket ' + bucket + ' &middot; ' + mult + '</strong>';
    if (isPush) {
        loseLine += ' &middot; <span>refund — net 0</span>';
    } else if (body && typeof body.net === 'number') {
        loseLine += ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>';
    }
    window.TobyCasinoResult.render({
        resultEl: resultEl,
        body: body,
        classPrefix: 'plinko',
        winLineHtml: winLine,
        loseLineHtml: loseLine,
    });
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('plinko', 'drop');
    if (!els) return;

    const board = document.getElementById('plinko-board');
    const pegsGroup = document.getElementById('plinko-pegs');
    const bucketsGroup = document.getElementById('plinko-buckets');
    const ball = document.getElementById('plinko-ball');
    const tableEl = document.querySelector('.plinko-table');
    if (!els.form || !els.primaryBtn || !els.stakeInput || !board || !pegsGroup || !bucketsGroup || !ball) return;

    const SVG_NS = 'http://www.w3.org/2000/svg';
    const ROWS = Number(window.__plinkoRows) || 8;
    const BUCKETS = Number(window.__plinkoBuckets) || (ROWS + 1);
    const TABLES = window.__plinkoTables || {};

    // SVG viewBox is -100..100 horizontally so the centre lines up with
    // x=0; buckets fill the full 200 width. Vertical layout below.
    const BOARD_W = 200;
    const PEG_X = BOARD_W / BUCKETS;      // horizontal peg spacing
    const PEG_Y_TOP = 18;                  // y of row 0
    const PEG_Y_STEP = 18;                 // y delta between rows
    const BUCKET_Y_TOP = PEG_Y_TOP + ROWS * PEG_Y_STEP + 4;
    const BUCKET_H = 28;
    const BALL_R = 4;
    const DROP_MS = 1400;

    function selectedRisk() {
        const checked = els.form.querySelector('input[name="risk"]:checked');
        return checked ? checked.value : null;
    }

    function bucketCenterX(k) {
        return -BOARD_W / 2 + (k + 0.5) * PEG_X;
    }

    function formatMult(m) {
        if (m === Math.floor(m)) return m + '×';
        // Trim trailing zeros: 0.40 → 0.4, 1.50 → 1.5.
        return m.toFixed(2).replace(/\.?0+$/, '') + '×';
    }

    // Tint buckets by tier: 0 (bust) muted red, <1 (partial loss) muted
    // orange, 1 (push) neutral, >1 (win) green. Keeps the table readable
    // at a glance without forcing the player to know the multiplier ranges.
    function bucketClass(mult) {
        if (mult === 0) return 'plinko-bucket-bust';
        if (mult < 1)   return 'plinko-bucket-loss';
        if (mult === 1) return 'plinko-bucket-push';
        if (mult >= 10) return 'plinko-bucket-jackpot';
        return 'plinko-bucket-win';
    }

    function renderPegs() {
        pegsGroup.innerHTML = '';
        // Row k has k+1 pegs centred on x=0. Offset by half-peg so the
        // bottom row's pegs sit at the boundaries between buckets.
        for (let row = 0; row < ROWS; row++) {
            const y = PEG_Y_TOP + row * PEG_Y_STEP;
            const count = row + 1;
            const startX = -((count - 1) / 2) * PEG_X;
            for (let i = 0; i < count; i++) {
                const peg = document.createElementNS(SVG_NS, 'circle');
                peg.setAttribute('cx', (startX + i * PEG_X).toFixed(2));
                peg.setAttribute('cy', y.toFixed(2));
                peg.setAttribute('r', '1.6');
                peg.setAttribute('class', 'plinko-peg');
                peg.setAttribute('fill', 'url(#plinko-peg-grad)');
                pegsGroup.appendChild(peg);
            }
        }
    }

    function renderBuckets(risk) {
        bucketsGroup.innerHTML = '';
        const table = TABLES[risk] || [];
        const bucketW = PEG_X * 0.92;
        for (let k = 0; k < BUCKETS; k++) {
            const cx = bucketCenterX(k);
            const mult = table[k];
            const rect = document.createElementNS(SVG_NS, 'rect');
            rect.setAttribute('x', (cx - bucketW / 2).toFixed(2));
            rect.setAttribute('y', BUCKET_Y_TOP.toFixed(2));
            rect.setAttribute('width', bucketW.toFixed(2));
            rect.setAttribute('height', BUCKET_H.toFixed(2));
            rect.setAttribute('rx', '3');
            rect.setAttribute('class', 'plinko-bucket-rect ' + (typeof mult === 'number' ? bucketClass(mult) : ''));
            rect.setAttribute('data-bucket', String(k));
            bucketsGroup.appendChild(rect);

            const text = document.createElementNS(SVG_NS, 'text');
            text.setAttribute('x', cx.toFixed(2));
            text.setAttribute('y', (BUCKET_Y_TOP + BUCKET_H / 2 + 3).toFixed(2));
            text.setAttribute('class', 'plinko-bucket-label');
            text.textContent = typeof mult === 'number' ? formatMult(mult) : '?';
            bucketsGroup.appendChild(text);
        }
    }

    function clearLandedHighlight() {
        bucketsGroup.querySelectorAll('.plinko-bucket-landed').forEach(el => {
            el.classList.remove('plinko-bucket-landed');
        });
    }

    function highlightLanded(k) {
        clearLandedHighlight();
        const rect = bucketsGroup.querySelector('rect[data-bucket="' + k + '"]');
        if (rect) rect.classList.add('plinko-bucket-landed');
    }

    /**
     * Build a sequence of `ROWS` left/right decisions (-1 / +1) that
     * sum to `K - ROWS/2` right-shifts, picking K out of ROWS as right.
     * Shuffled with a per-drop random seed so successive drops to the
     * same bucket don't trace the identical path.
     */
    function decisionPath(bucket) {
        const rights = bucket;        // # right-shifts → bucket index
        const lefts = ROWS - rights;
        const seq = [];
        for (let i = 0; i < rights; i++) seq.push(1);
        for (let i = 0; i < lefts; i++) seq.push(-1);
        for (let i = seq.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            const tmp = seq[i]; seq[i] = seq[j]; seq[j] = tmp;
        }
        return seq;
    }

    // easeInOutQuad — gentle acceleration into and out of each row so
    // the bounce reads as a real ball rather than a linear lerp.
    function ease(t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    function animateDrop(targetBucket) {
        const path = decisionPath(targetBucket);
        // X waypoints — start at (0, -BALL_R), then after each row k the
        // ball is at `cum * PEG_X / 2` where cum = sum(decisions[0..k]).
        // Each waypoint y sits just below the row's pegs.
        const waypoints = [{ x: 0, y: -BALL_R }];
        let cum = 0;
        for (let row = 0; row < ROWS; row++) {
            cum += path[row];
            waypoints.push({
                x: (cum * PEG_X) / 2,
                y: PEG_Y_TOP + row * PEG_Y_STEP + PEG_Y_STEP / 2,
            });
        }
        // Final waypoint: settle into the bucket centre.
        waypoints.push({ x: bucketCenterX(targetBucket), y: BUCKET_Y_TOP + BUCKET_H / 2 });

        ball.hidden = false;
        ball.setAttribute('cx', '0');
        ball.setAttribute('cy', '0');
        ball.setAttribute('transform', 'translate(' + waypoints[0].x + ',' + waypoints[0].y + ')');

        const segMs = DROP_MS / (waypoints.length - 1);
        const startedAt = performance.now();

        return new Promise(resolve => {
            function tick(now) {
                const elapsed = now - startedAt;
                if (elapsed >= DROP_MS) {
                    const end = waypoints[waypoints.length - 1];
                    ball.setAttribute('transform', 'translate(' + end.x + ',' + end.y + ')');
                    resolve();
                    return;
                }
                const segIdx = Math.min(Math.floor(elapsed / segMs), waypoints.length - 2);
                const segStart = waypoints[segIdx];
                const segEnd = waypoints[segIdx + 1];
                const segT = ease((elapsed - segIdx * segMs) / segMs);
                const x = segStart.x + (segEnd.x - segStart.x) * segT;
                const y = segStart.y + (segEnd.y - segStart.y) * segT;
                ball.setAttribute('transform', 'translate(' + x.toFixed(2) + ',' + y.toFixed(2) + ')');
                requestAnimationFrame(tick);
            }
            requestAnimationFrame(tick);
        });
    }

    // Initial paint.
    renderPegs();
    renderBuckets(selectedRisk());

    // Re-paint bucket labels when the risk profile changes so the player
    // sees the multipliers they're actually playing for before pressing Drop.
    els.form.querySelectorAll('input[name="risk"]').forEach(input => {
        input.addEventListener('change', () => {
            clearLandedHighlight();
            renderBuckets(selectedRisk());
        });
    });

    let pendingResolve = null;

    function startAnimation() {
        clearLandedHighlight();
        if (window.CasinoSounds) window.CasinoSounds.play('deal');
        // The drop animation can't start until we know which bucket to
        // aim for — we kick it off from stopAnimation once the server
        // response arrives. Here we just clear stale state.
        return null;
    }

    function stopAnimation(_intervalId, body) {
        if (!body || typeof body.bucket !== 'number') {
            ball.hidden = true;
            return;
        }
        animateDrop(body.bucket).then(() => {
            highlightLanded(body.bucket);
            if (window.CasinoSounds) window.CasinoSounds.play('click');
            if (pendingResolve) {
                const r = pendingResolve;
                pendingResolve = null;
                r();
            }
        });
    }

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/plinko/drop',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        minSettleMs: DROP_MS,
        failureMessage: 'Drop failed.',
        validate: function () {
            if (!selectedRisk()) return 'Pick a risk profile first.';
            return null;
        },
        buildPayload: function (state) {
            return {
                stake: state.stake,
                risk: selectedRisk(),
                autoTopUp: state.autoTopUp,
            };
        },
        startAnimation: startAnimation,
        stopAnimation: stopAnimation,
        renderResult: function (body) { renderPlinkoResult(els.resultEl, body); },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderPlinkoResult };
}
