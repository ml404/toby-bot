// Spinning-wheel renderer + animator shown when a jackpot roll hits.
// Server picks the tier (authoritative); this JS just animates the
// wheel landing on the matching wedge, so the player can SEE which
// slice of the pool they're getting and why.
//
// Segments come from `window.__jackpotWheel` (a JSON blob the
// `fragments/casino.html :: jackpotWheel` template inlines on every
// casino page). Each entry is `{weight, payoutPct}` matching the
// server-side `database.economy.JackpotWheel.Segment` row, so the
// wheel the player sees IS the wheel the server spun.
//
// Wheel layout: instead of one weight-proportional wedge per tier
// (which renders as a giant pity arc that looks static when it spins),
// we lay out `SPOKE_COUNT` equal-sized spokes around the rim and
// distribute them across tiers proportionally to weight. Higher-weight
// tiers own more spokes; the spokes are interleaved so colours
// alternate as the wheel rotates, which is what makes the spin read
// as motion instead of a rotating yellow blob.
//
// Spin is an affirmative action: the overlay reveals the wheel +
// `SPIN` button, then nothing animates until the player clicks the
// button. Lets the player savour the moment instead of being shown a
// cutscene.
//
// Coordinated with `casino-jackpot.js`'s `holdPoolBanner` /
// `releasePoolBanner` so the live pool counter doesn't tick down to
// the post-win value mid-spin — that would let a fast reader infer
// the result before the wheel stops. Hold fires on SPIN click (not on
// reveal) so the banner doesn't freeze while the player decides.

(function (root) {
    'use strict';

    const SPIN_DURATION_MS = 3200;
    const SPIN_ROTATIONS = 4;
    const SPOKE_COUNT = 24;
    // CSS easing curve for the rotor transition. Tick scheduling
    // inverts this so peg-pass clicks fire in lockstep with the visual
    // — fast at first, slowing as the wheel decelerates.
    const EASE_X1 = 0.18, EASE_Y1 = 0.6, EASE_X2 = 0.18, EASE_Y2 = 1.0;

    function bezierY(u, y1, y2) {
        const u2 = 1 - u;
        return 3 * u2 * u2 * u * y1 + 3 * u2 * u * u * y2 + u * u * u;
    }
    function bezierX(u, x1, x2) {
        const u2 = 1 - u;
        return 3 * u2 * u2 * u * x1 + 3 * u2 * u * u * x2 + u * u * u;
    }
    // Find the time-fraction (X) at which the easing reaches Y =
    // targetY. Y(u) is monotonic for our curve so binary search is
    // safe and 24 iterations gets us well below per-frame precision.
    function easedTimeAtValue(targetY) {
        let lo = 0, hi = 1;
        for (let i = 0; i < 24; i++) {
            const mid = (lo + hi) / 2;
            if (bezierY(mid, EASE_Y1, EASE_Y2) < targetY) lo = mid; else hi = mid;
        }
        return bezierX((lo + hi) / 2, EASE_X1, EASE_X2);
    }
    // Casino-leaning palette — slightly desaturated vs the original
    // neon set so the wheel reads as "felt-and-brass" rather than
    // arcade. Cycles per tier (not per spoke) so each tier has one
    // consistent colour the player can recognise across rim sectors.
    const COLOURS = ['#e8b923', '#6b4fd6', '#16a085', '#d96089', '#4a5b6e',
                     '#c97f24', '#2c7fbf', '#27ae60', '#a83333', '#7a3da8',
                     '#b5901a', '#138a72'];

    function readSegments() {
        const raw = (root && root.__jackpotWheel) || [];
        if (!Array.isArray(raw) || raw.length === 0) return [];
        // Defensive clone + filter out malformed rows so the wheel
        // never renders a bad wedge if the server somehow emits one.
        return raw.filter(s =>
            s && typeof s.weight === 'number' && s.weight > 0 &&
            typeof s.payoutPct === 'number' && s.payoutPct > 0
        ).map(s => ({ weight: s.weight, payoutPct: s.payoutPct }));
    }

    /**
     * Hamilton's largest-remainder method: distribute SPOKE_COUNT spokes
     * across tiers proportional to weight. Every tier with weight > 0
     * is guaranteed at least one spoke so rare tiers stay visible.
     */
    function allocateSpokes(segments) {
        const totalWeight = segments.reduce((s, seg) => s + seg.weight, 0);
        if (totalWeight <= 0 || segments.length === 0) return [];
        // If there are more tiers than spokes we can't satisfy the
        // at-least-1 guarantee for every tier; fall back to one spoke
        // per tier, truncated.
        if (segments.length >= SPOKE_COUNT) {
            return segments.slice(0, SPOKE_COUNT).map(() => 1);
        }
        const counts = new Array(segments.length).fill(1);
        let allocated = segments.length;
        const ideals = segments.map(seg => (seg.weight / totalWeight) * SPOKE_COUNT);
        const remainders = ideals.map((ideal, i) => ({ i, frac: ideal - Math.floor(ideal) }));
        // Floor allocation on top of the reserved 1, but only the
        // portion above 1 — we already gave each tier its base spoke.
        for (let i = 0; i < segments.length; i++) {
            const extra = Math.max(0, Math.floor(ideals[i]) - 1);
            counts[i] += extra;
            allocated += extra;
        }
        // Distribute leftover spokes to the tiers with the largest
        // fractional remainders (classic Hamilton step).
        remainders.sort((a, b) => b.frac - a.frac);
        for (const r of remainders) {
            if (allocated >= SPOKE_COUNT) break;
            counts[r.i]++;
            allocated++;
        }
        // If we overshot (rounding edges with at-least-1), trim from
        // the largest count >1 until we're back at SPOKE_COUNT.
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

    /**
     * Place each tier's spokes around the rim so they're spread out
     * (not clumped). Largest tier seeds first at evenly-spaced
     * positions; smaller tiers fill the remaining slots, sliding
     * forward when a slot is taken. Result is an array of length
     * SPOKE_COUNT mapping each slot to a tier index.
     */
    function placeSpokes(counts) {
        const N = counts.reduce((s, c) => s + c, 0);
        const slots = new Array(N).fill(-1);
        const order = counts
            .map((c, i) => ({ i, c }))
            .filter(t => t.c > 0)
            .sort((a, b) => b.c - a.c);
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
            // Offset the next tier's starting position so its spokes
            // don't all collide with the previous tier's slot 0.
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
        return `M 0 0 L ${x0.toFixed(2)} ${y0.toFixed(2)} A ${radius} ${radius} 0 ${largeArc} 1 ${x1.toFixed(2)} ${y1.toFixed(2)} Z`;
    }

    /**
     * Render the spokes into `rotor`. Returns an array of {tierIndex,
     * mid} per spoke so `spinTo` can pick a target angle.
     */
    function render(segments, rotor) {
        rotor.innerHTML = '';
        const counts = allocateSpokes(segments);
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
            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', buildWedgePath(start, end, radius));
            path.setAttribute('fill', COLOURS[tierIndex % COLOURS.length]);
            path.setAttribute('class', 'jackpot-wheel-segment');
            rotor.appendChild(path);

            // Label sits near the rim so labels remain legible even when
            // the spokes get thin. Rotate the text to read radially.
            const mid = (start + end) / 2;
            const labelR = radius * 0.72;
            const lx = Math.cos((mid - 90) * Math.PI / 180) * labelR;
            const ly = Math.sin((mid - 90) * Math.PI / 180) * labelR;
            const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            text.setAttribute('x', lx.toFixed(2));
            text.setAttribute('y', ly.toFixed(2));
            text.setAttribute('class', 'jackpot-wheel-segment-label');
            text.setAttribute('transform', `rotate(${mid.toFixed(2)} ${lx.toFixed(2)} ${ly.toFixed(2)})`);
            text.textContent = segments[tierIndex].payoutPct + '%';
            rotor.appendChild(text);

            spokes.push({ tierIndex, mid });
        }
        // Brass pegs at every spoke boundary, riding inside the rotor
        // so they spin with the wheel. The pointer's tip sits at the
        // peg radius, so visually each peg flicks past the pointer as
        // the wheel rotates — that's the "real wheel" cue.
        const pegRadius = 94;
        for (let s = 0; s < N; s++) {
            const angle = s * sweep;
            const px = Math.cos((angle - 90) * Math.PI / 180) * pegRadius;
            const py = Math.sin((angle - 90) * Math.PI / 180) * pegRadius;
            const peg = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
            peg.setAttribute('cx', px.toFixed(2));
            peg.setAttribute('cy', py.toFixed(2));
            peg.setAttribute('r', '2.6');
            peg.setAttribute('class', 'jackpot-wheel-peg');
            rotor.appendChild(peg);
        }
        return spokes;
    }

    function tierLabel(payoutPct) {
        if (payoutPct >= 30) return '🎰 MEGA JACKPOT!';
        if (payoutPct >= 10) return '🎰 BIG WIN!';
        if (payoutPct >= 2)  return '💰 Nice payout!';
        return '🎟️ Pity prize';
    }

    function prefersReducedMotion() {
        return typeof window !== 'undefined' && window.matchMedia &&
            window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    }

    /**
     * Reveal the wheel overlay aimed at `tierIndex` and wait for the
     * player to press SPIN. `onSettle` fires after the spin completes
     * — or immediately if the player dismisses the overlay without
     * spinning, so caller-side bookkeeping still runs deterministically.
     */
    function spinTo(tierIndex, payoutAmount, payoutPct, onSettle) {
        const overlay = document.getElementById('jackpot-wheel-overlay');
        if (!overlay) {
            // No overlay on this page (e.g. Discord-only or test
            // harness) — fire onSettle synchronously so the calling
            // game's renderer can paint its result line unchanged.
            if (typeof onSettle === 'function') onSettle();
            return;
        }
        const segments = readSegments();
        const rotor = overlay.querySelector('.jackpot-wheel-rotor');
        const resultEl = overlay.querySelector('.jackpot-wheel-result');
        const spinBtn = overlay.querySelector('.jackpot-wheel-spin-btn');
        if (!rotor || segments.length === 0 || tierIndex < 0 || tierIndex >= segments.length) {
            if (typeof onSettle === 'function') onSettle();
            return;
        }
        const spokes = render(segments, rotor);
        // Pick the first spoke owned by the chosen tier. Deterministic
        // so tests can assert a known target angle.
        const targetSpoke = spokes.find(s => s.tierIndex === tierIndex);
        if (!targetSpoke) {
            if (typeof onSettle === 'function') onSettle();
            return;
        }
        const target = targetSpoke.mid;
        // Pointer is at the top (angle 0 in wedge coords). To bring
        // the wedge midpoint under it we rotate by `-target`, plus
        // full rotations for drama.
        const finalAngle = SPIN_ROTATIONS * 360 - target;

        // Snap rotor to 0 before animating so successive spins start
        // from a known transform. Force a layout read so the browser
        // registers the transition's starting point.
        rotor.style.transition = 'none';
        rotor.style.transform = 'rotate(0deg)';
        // eslint-disable-next-line no-unused-expressions
        rotor.getBoundingClientRect();
        rotor.style.transition = '';

        overlay.hidden = false;
        if (resultEl) resultEl.textContent = '';

        let settled = false;
        let spinStarted = false;
        const tickTimeouts = [];

        const clearTicks = () => {
            while (tickTimeouts.length) clearTimeout(tickTimeouts.pop());
        };

        const settle = () => {
            if (settled) return;
            settled = true;
            clearTicks();
            rotor.removeEventListener('transitionend', settle);
            if (resultEl) resultEl.textContent = tierLabel(payoutPct) + ' +' + payoutAmount + ' credits';
            if (root && root.TobyJackpot && typeof root.TobyJackpot.releasePoolBanner === 'function') {
                root.TobyJackpot.releasePoolBanner();
            }
            // Auto-dismiss after a short read-pause so the player sees
            // the result before returning to the table. Manual close
            // via backdrop click also works for impatient users.
            const close = () => {
                overlay.hidden = true;
                overlay.removeEventListener('click', backdropAfterSettle);
            };
            const backdropAfterSettle = (ev) => {
                if (ev.target === overlay) close();
            };
            overlay.addEventListener('click', backdropAfterSettle);
            setTimeout(close, 1800);
            if (typeof onSettle === 'function') onSettle();
        };

        const startSpin = () => {
            if (spinStarted || settled) return;
            spinStarted = true;
            if (spinBtn) {
                spinBtn.disabled = true;
                spinBtn.removeEventListener('click', startSpin);
            }
            overlay.removeEventListener('click', backdropDismiss);
            // Hold the pool banner once the wheel actually starts
            // moving so the displayed pool doesn't tick down to the
            // post-win value mid-spin.
            if (root && root.TobyJackpot && typeof root.TobyJackpot.holdPoolBanner === 'function') {
                root.TobyJackpot.holdPoolBanner();
            }
            if (prefersReducedMotion()) {
                // Skip the 3.2s transition — the affirmative click was
                // the moment, the rotation is just decoration.
                rotor.style.transition = 'none';
                rotor.style.transform = 'rotate(' + finalAngle.toFixed(2) + 'deg)';
                settle();
                return;
            }
            rotor.addEventListener('transitionend', settle);
            // Belt-and-braces fallback in case transitionend doesn't
            // fire (overlay torn down mid-spin, GPU stalls, etc.).
            setTimeout(settle, SPIN_DURATION_MS + 600);
            // Schedule a "tick" sound for every peg the pointer passes,
            // timed against the same easing curve as the rotor so the
            // clicks decelerate with the wheel. Cancelled in settle so
            // stragglers don't fire after a fast dismiss.
            const pegStep = 360 / SPOKE_COUNT;
            const numTicks = Math.floor(finalAngle / pegStep);
            for (let k = 1; k <= numTicks; k++) {
                const eased = (k * pegStep) / finalAngle;
                const ms = easedTimeAtValue(eased) * SPIN_DURATION_MS;
                tickTimeouts.push(setTimeout(() => {
                    if (root && root.CasinoSounds && typeof root.CasinoSounds.play === 'function') {
                        root.CasinoSounds.play('tick');
                    }
                }, ms));
            }
            requestAnimationFrame(() => {
                rotor.style.transform = 'rotate(' + finalAngle.toFixed(2) + 'deg)';
            });
        };

        // Backdrop click before the player has chosen to spin — dismiss
        // cleanly. The underlying game's result line is already
        // painted, so nothing's gated on this; onSettle still fires so
        // any caller-side bookkeeping runs.
        const backdropDismiss = (ev) => {
            if (ev.target !== overlay || spinStarted) return;
            overlay.removeEventListener('click', backdropDismiss);
            if (spinBtn) spinBtn.removeEventListener('click', startSpin);
            clearTicks();
            overlay.hidden = true;
            if (typeof onSettle === 'function') onSettle();
        };

        if (spinBtn) {
            spinBtn.disabled = false;
            spinBtn.addEventListener('click', startSpin);
        } else {
            // Older overlay markup with no SPIN button — preserve the
            // auto-spin behaviour so we don't break callers/tests that
            // haven't updated yet.
            startSpin();
        }
        overlay.addEventListener('click', backdropDismiss);
    }

    const api = { readSegments, allocateSpokes, placeSpokes, render, tierLabel, spinTo, SPIN_DURATION_MS, SPOKE_COUNT };
    if (root) root.TobyJackpotWheel = api;

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
