// Wheel of Fortune renderer + animator.
//
// SVG layout mirrors the jackpot wheel (`fragments/casino.html :: jackpotBanner`):
// brass rim, hub, golden pointer, spoked rotor with pegs. The rotor's
// spokes are allocated proportional to the multiplier weights the server
// holds, so the visible wheel IS the wheel the server spun against.
//
// Spoke layout: SPOKE_COUNT equal-sized wedges around the rim, allocated
// across multiplier tiers via Hamilton's largest-remainder method —
// same approach `casino-jackpot-wheel.js` uses so visual proportions
// match probability proportions. Higher-weight tiers own more spokes;
// the spokes are interleaved so colours alternate as the wheel spins.
//
// Spin is server-authoritative: the player presses Spin, we POST the
// stake + pick, the server returns the landed multiplier, and the JS
// rotates the rotor to bring a wedge of that multiplier under the
// pointer. Pure animation — the result is decided before the wheel
// starts moving.

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

    const rotor = document.getElementById('wheel-rotor');
    const statusEl = document.getElementById('wheel-status');
    const tableEl = document.querySelector('.wheel-table');
    if (!els.form || !els.primaryBtn || !els.stakeInput || !rotor) return;

    const SVG_NS = 'http://www.w3.org/2000/svg';
    const SPOKE_COUNT = 24;
    const SPIN_DURATION_MS = 2200;
    const SPIN_ROTATIONS = 4;

    // One colour per multiplier tier so all spokes of the same multiplier
    // are visually grouped — keeps the wheel readable as it spins.
    // Order matches the picks ascending: 2× green, 3× blue, 5× purple, 10× gold.
    const TIER_COLOURS = ['#27ae60', '#2c7fbf', '#6b4fd6', '#e8b923'];

    function readWeights() {
        const raw = (window && window.__wofWheelWeights) || [];
        if (!Array.isArray(raw)) return [];
        return raw
            .filter(t => t && typeof t.multiplier === 'number' && typeof t.slots === 'number' && t.slots > 0)
            .map(t => ({ multiplier: t.multiplier, slots: t.slots }))
            .sort((a, b) => a.multiplier - b.multiplier);
    }

    // Hamilton's largest-remainder method, copied verbatim from
    // casino-jackpot-wheel.js. Distributes SPOKE_COUNT spokes across
    // tiers proportional to weight, with each tier guaranteed at least 1.
    function allocateSpokes(tiers) {
        const totalWeight = tiers.reduce((s, t) => s + t.slots, 0);
        if (totalWeight <= 0 || tiers.length === 0) return [];
        if (tiers.length >= SPOKE_COUNT) {
            return tiers.slice(0, SPOKE_COUNT).map(() => 1);
        }
        const counts = new Array(tiers.length).fill(1);
        let allocated = tiers.length;
        const ideals = tiers.map(t => (t.slots / totalWeight) * SPOKE_COUNT);
        const remainders = ideals.map((ideal, i) => ({ i, frac: ideal - Math.floor(ideal) }));
        for (let i = 0; i < tiers.length; i++) {
            const extra = Math.max(0, Math.floor(ideals[i]) - 1);
            counts[i] += extra;
            allocated += extra;
        }
        remainders.sort((a, b) => b.frac - a.frac);
        for (const r of remainders) {
            if (allocated >= SPOKE_COUNT) break;
            counts[r.i]++;
            allocated++;
        }
        while (allocated > SPOKE_COUNT) {
            let maxIdx = -1, maxCount = 1;
            for (let i = 0; i < counts.length; i++) {
                if (counts[i] > maxCount) { maxIdx = i; maxCount = counts[i]; }
            }
            if (maxIdx < 0) break;
            counts[maxIdx]--;
            allocated--;
        }
        return counts;
    }

    // Spread each tier's spokes around the rim so high-weight tiers
    // don't form one giant arc — same placement algo as the jackpot wheel.
    function placeSpokes(counts) {
        const N = counts.reduce((s, c) => s + c, 0);
        const slots = new Array(N).fill(-1);
        const order = counts.map((c, i) => ({ i, c })).filter(t => t.c > 0).sort((a, b) => b.c - a.c);
        let phase = 0;
        for (const { i, c } of order) {
            const step = N / c;
            let pos = phase;
            for (let j = 0; j < c; j++) {
                let slot = Math.round(pos) % N;
                let tries = 0;
                while (slots[slot] !== -1 && tries < N) {
                    slot = (slot + 1) % N;
                    tries++;
                }
                slots[slot] = i;
                pos += step;
            }
            phase += step / 2;
        }
        return slots;
    }

    function buildWedgePath(startAngle, endAngle, radius) {
        const a0 = (startAngle - 90) * Math.PI / 180;
        const a1 = (endAngle - 90) * Math.PI / 180;
        const x0 = Math.cos(a0) * radius;
        const y0 = Math.sin(a0) * radius;
        const x1 = Math.cos(a1) * radius;
        const y1 = Math.sin(a1) * radius;
        const largeArc = (endAngle - startAngle) > 180 ? 1 : 0;
        return 'M 0 0 L ' + x0.toFixed(2) + ' ' + y0.toFixed(2) +
            ' A ' + radius + ' ' + radius + ' 0 ' + largeArc + ' 1 ' +
            x1.toFixed(2) + ' ' + y1.toFixed(2) + ' Z';
    }

    function renderWheel(tiers) {
        rotor.innerHTML = '';
        const counts = allocateSpokes(tiers);
        if (counts.length === 0) return [];
        const slots = placeSpokes(counts);
        const N = slots.length;
        const sweep = 360 / N;
        const radius = 100;
        const spokes = [];
        for (let s = 0; s < N; s++) {
            const tierIndex = slots[s];
            const start = s * sweep;
            const end = start + sweep;
            const path = document.createElementNS(SVG_NS, 'path');
            path.setAttribute('d', buildWedgePath(start, end, radius));
            path.setAttribute('fill', TIER_COLOURS[tierIndex % TIER_COLOURS.length]);
            path.setAttribute('class', 'wheel-segment');
            rotor.appendChild(path);

            const mid = (start + end) / 2;
            const labelR = radius * 0.72;
            const lx = Math.cos((mid - 90) * Math.PI / 180) * labelR;
            const ly = Math.sin((mid - 90) * Math.PI / 180) * labelR;
            const text = document.createElementNS(SVG_NS, 'text');
            text.setAttribute('x', lx.toFixed(2));
            text.setAttribute('y', ly.toFixed(2));
            text.setAttribute('class', 'wheel-segment-label');
            text.setAttribute('transform', 'rotate(' + mid.toFixed(2) + ' ' + lx.toFixed(2) + ' ' + ly.toFixed(2) + ')');
            text.textContent = tiers[tierIndex].multiplier + '×';
            rotor.appendChild(text);

            spokes.push({ tierIndex, mid, multiplier: tiers[tierIndex].multiplier });
        }
        // Brass pegs at every spoke boundary, riding inside the rotor so
        // they spin with the wheel — same visual cue the jackpot wheel uses.
        const pegRadius = 94;
        for (let s = 0; s < N; s++) {
            const angle = s * sweep;
            const px = Math.cos((angle - 90) * Math.PI / 180) * pegRadius;
            const py = Math.sin((angle - 90) * Math.PI / 180) * pegRadius;
            const peg = document.createElementNS(SVG_NS, 'circle');
            peg.setAttribute('cx', px.toFixed(2));
            peg.setAttribute('cy', py.toFixed(2));
            peg.setAttribute('r', '2.6');
            peg.setAttribute('class', 'wheel-peg');
            rotor.appendChild(peg);
        }
        return spokes;
    }

    function prefersReducedMotion() {
        return typeof window !== 'undefined' && window.matchMedia &&
            window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    }

    let currentSpokes = renderWheel(readWeights());

    // Re-paint the picker selection's spoke colour into the picker chip
    // so the player sees which wedges they're betting on as they
    // toggle the pick radio.
    function tintPickerChips() {
        const tiers = readWeights();
        els.form.querySelectorAll('.wheel-pick span[data-multiplier]').forEach(el => {
            const mult = Number(el.getAttribute('data-multiplier'));
            const idx = tiers.findIndex(t => t.multiplier === mult);
            if (idx >= 0) {
                el.style.setProperty('--wheel-pick-tint', TIER_COLOURS[idx % TIER_COLOURS.length]);
            }
        });
    }
    tintPickerChips();

    function selectedPick() {
        const checked = els.form.querySelector('input[name="pick"]:checked');
        return checked ? parseInt(checked.value, 10) : null;
    }

    function startAnimation() {
        if (statusEl) statusEl.textContent = 'Spinning…';
        if (window.CasinoSounds) window.CasinoSounds.play('deal');
        return null;
    }

    function spinToMultiplier(landed) {
        if (!currentSpokes.length) return Promise.resolve();
        const target = currentSpokes.find(s => s.multiplier === landed);
        if (!target) return Promise.resolve();
        // Pointer sits at angle 0 (top of the wheel). To bring the wedge
        // midpoint under it we rotate by `360 - mid`, plus full rotations
        // for drama. Snap rotor back to 0 first so successive spins
        // start from a known transform — same dance casino-jackpot-wheel
        // uses to avoid accumulated-rotation drift. The CSS transition
        // is owned by `.wheel-rotor` (not inline) so the browser keeps a
        // stable starting state across both the snap and the spin.
        const finalAngle = SPIN_ROTATIONS * 360 + (360 - (target.mid % 360));
        rotor.style.transition = 'none';
        rotor.style.transform = 'rotate(0deg)';
        // Force a layout read so the browser registers the snap-back
        // as a discrete style change before the CSS transition kicks
        // in. Without this, the browser collapses both transform writes
        // into the easing curve and the wheel "spins" by zero degrees.
        // eslint-disable-next-line no-unused-expressions
        rotor.getBoundingClientRect();
        rotor.style.transition = '';  // restore the CSS transition
        if (prefersReducedMotion()) {
            rotor.style.transition = 'none';
            rotor.style.transform = 'rotate(' + finalAngle + 'deg)';
            return Promise.resolve();
        }
        return new Promise(resolve => {
            const onEnd = () => {
                rotor.removeEventListener('transitionend', onEnd);
                resolve();
            };
            rotor.addEventListener('transitionend', onEnd);
            // Belt-and-braces: resolve even if transitionend doesn't fire
            // (tab switch mid-spin, GPU stalls, etc.).
            setTimeout(() => {
                rotor.removeEventListener('transitionend', onEnd);
                resolve();
            }, SPIN_DURATION_MS + 400);
            requestAnimationFrame(() => {
                rotor.style.transform = 'rotate(' + finalAngle + 'deg)';
            });
        });
    }

    function stopAnimation(_intervalId, body) {
        if (!body || body.landed == null) {
            if (statusEl) statusEl.textContent = 'Pick a multiplier and press Spin.';
            return;
        }
        spinToMultiplier(body.landed).then(() => {
            if (statusEl) {
                statusEl.textContent = body.win
                    ? 'Landed ' + body.landed + '× — match!'
                    : 'Landed ' + body.landed + '× — no match.';
            }
            if (window.CasinoSounds) window.CasinoSounds.play('click');
        });
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
            return {
                stake: state.stake,
                pick: selectedPick(),
                autoTopUp: state.autoTopUp,
            };
        },
        startAnimation: startAnimation,
        stopAnimation: stopAnimation,
        renderResult: function (body) { renderWheelResult(els.resultEl, body); },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderWheelResult };
}
