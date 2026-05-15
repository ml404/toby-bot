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
// Coordinated with `casino-jackpot.js`'s `holdPoolBanner` /
// `releasePoolBanner` so the live pool counter doesn't tick down to
// the post-win value mid-spin — that would let a fast reader infer
// the result before the wheel stops.

(function (root) {
    'use strict';

    const SPIN_DURATION_MS = 3200;
    const SPIN_ROTATIONS = 4;
    // Palette cycles through wedges; gold, indigo, teal, pink, slate
    // give enough contrast at 5 segments and remain readable at 12.
    const COLOURS = ['#f1c40f', '#7c5cff', '#1abc9c', '#ff6b9d', '#5d6d7e',
                     '#e67e22', '#3498db', '#27ae60', '#c0392b', '#8e44ad',
                     '#d4ac0d', '#16a085'];

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

    function render(segments, rotor) {
        rotor.innerHTML = '';
        const totalWeight = segments.reduce((s, seg) => s + seg.weight, 0);
        if (totalWeight <= 0) return [];
        const radius = 100;
        let cursor = 0;
        const angles = [];
        segments.forEach((seg, i) => {
            const sweep = (seg.weight / totalWeight) * 360;
            const start = cursor;
            const end = cursor + sweep;
            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', buildWedgePath(start, end, radius));
            path.setAttribute('fill', COLOURS[i % COLOURS.length]);
            path.setAttribute('class', 'jackpot-wheel-segment');
            rotor.appendChild(path);

            // Label at the wedge's midpoint, rotated to read radially.
            const mid = (start + end) / 2;
            const labelR = radius * 0.62;
            const lx = Math.cos((mid - 90) * Math.PI / 180) * labelR;
            const ly = Math.sin((mid - 90) * Math.PI / 180) * labelR;
            const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            text.setAttribute('x', lx.toFixed(2));
            text.setAttribute('y', ly.toFixed(2));
            text.setAttribute('class', 'jackpot-wheel-segment-label');
            text.textContent = seg.payoutPct + '%';
            rotor.appendChild(text);

            angles.push({ start, end, mid });
            cursor = end;
        });
        return angles;
    }

    function tierLabel(payoutPct) {
        if (payoutPct >= 30) return '🎰 MEGA JACKPOT!';
        if (payoutPct >= 10) return '🎰 BIG WIN!';
        if (payoutPct >= 2)  return '💰 Nice payout!';
        return '🎟️ Pity prize';
    }

    /**
     * Show the overlay and spin to [tierIndex]. The wheel's rotor
     * rotates `SPIN_ROTATIONS` full turns + the angle needed to bring
     * the picked wedge's midpoint under the top pointer. [onSettle] is
     * called when the CSS transition completes.
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
        if (!rotor || segments.length === 0 || tierIndex < 0 || tierIndex >= segments.length) {
            if (typeof onSettle === 'function') onSettle();
            return;
        }
        const angles = render(segments, rotor);

        // Snap rotor to 0 before animating so successive spins start
        // from a known transform. Force a layout read between the snap
        // and the new target to make the browser register the
        // transition's starting point.
        rotor.style.transition = 'none';
        rotor.style.transform = 'rotate(0deg)';
        // eslint-disable-next-line no-unused-expressions
        rotor.getBoundingClientRect();
        rotor.style.transition = '';

        const target = angles[tierIndex].mid;
        // Pointer is at the top (angle 0 in wedge coords). To bring
        // the wedge midpoint under it we rotate by `-target`, plus
        // full rotations for drama.
        const finalAngle = SPIN_ROTATIONS * 360 - target;
        overlay.hidden = false;
        if (resultEl) resultEl.textContent = '';

        // Hold the pool banner so it doesn't tick to the post-win
        // value before the wheel lands.
        if (root && root.TobyJackpot && typeof root.TobyJackpot.holdPoolBanner === 'function') {
            root.TobyJackpot.holdPoolBanner();
        }

        let settled = false;
        const settle = () => {
            if (settled) return;
            settled = true;
            rotor.removeEventListener('transitionend', settle);
            if (resultEl) resultEl.textContent = tierLabel(payoutPct) + ' +' + payoutAmount + ' credits';
            if (root && root.TobyJackpot && typeof root.TobyJackpot.releasePoolBanner === 'function') {
                root.TobyJackpot.releasePoolBanner();
            }
            // Auto-dismiss after a short read-pause so the player sees
            // the result before returning to the table. Manual close
            // via click also works for impatient users.
            const close = () => { overlay.hidden = true; overlay.removeEventListener('click', close); };
            overlay.addEventListener('click', close, { once: true });
            setTimeout(close, 1800);
            if (typeof onSettle === 'function') onSettle();
        };
        rotor.addEventListener('transitionend', settle);
        // Belt-and-braces fallback in case transitionend doesn't fire
        // (overlay torn down mid-spin, reduced-motion CSS, etc.).
        setTimeout(settle, SPIN_DURATION_MS + 600);

        // Schedule the spin on the next frame so the snap-to-0 above
        // takes effect first.
        requestAnimationFrame(() => {
            rotor.style.transform = 'rotate(' + finalAngle.toFixed(2) + 'deg)';
        });
    }

    const api = { readSegments, render, tierLabel, spinTo, SPIN_DURATION_MS };
    if (root) root.TobyJackpotWheel = api;

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
