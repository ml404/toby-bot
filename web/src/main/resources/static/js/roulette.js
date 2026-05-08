// Pure-DOM render for a /spin response. Hoisted out of the IIFE so a
// future jest test can drive it without booting the whole page (mirrors
// renderSlotsResult). The IIFE below calls it with the live result element.
function renderRouletteResult(resultEl, body, flashTargetEl) {
    if (typeof window !== 'undefined' && window.TobyCasinoResult) {
        var betLabel = body.betLabel || body.bet || 'bet';
        var pocketLabel = '#' + body.landed +
            (body.color ? ' ' + body.color.toLowerCase() : '');
        window.TobyCasinoResult.render({
            resultEl: resultEl,
            body: body,
            classPrefix: 'roulette',
            winLineHtml:
                '<strong>+' + body.net + ' credits</strong> &middot; ' +
                body.multiplier + '× on ' + betLabel + ' (landed ' + pocketLabel + ')',
            loseLineHtml:
                'Lost <strong>' + Math.abs(body.net) + ' credits</strong> &middot; ' +
                betLabel + ' (landed ' + pocketLabel + ')',
        });
    }
    if (typeof window === 'undefined' || !body) return;
    if (window.CasinoRender) {
        var payoutEstimate = (body.jackpotPayout > 0 ? body.jackpotPayout : body.net) || 0;
        var chipCount = Math.min(7, Math.max(3, Math.ceil(payoutEstimate / 100)));
        window.CasinoRender.flashWinPayout(flashTargetEl, body, chipCount);
    }
}

(function () {
    'use strict';

    var els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('roulette', 'spin');
    if (!els) return;

    var wheel = document.getElementById('roulette-wheel');
    var landedEl = document.getElementById('roulette-landed-pocket');
    var fieldset = document.getElementById('roulette-bet-fieldset');
    var straightPicker = document.getElementById('roulette-straight-picker');
    var straightInput = document.getElementById('roulette-straight');
    var tableEl = document.querySelector('.roulette-table');

    if (!els.form || !els.primaryBtn || !els.stakeInput || !wheel || !fieldset) return;

    var SETTLE_MS = 1600;          // wheel deceleration duration
    var SPIN_DPS = 1080;           // degrees per second during the constant-speed spin
    var ANIMATION_FRAME_MS = 16;   // ~60fps
    var REDUCED_MOTION = (function () {
        try {
            return window.matchMedia &&
                window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        } catch (_) { return false; }
    })();

    // Wheel order parsed from the data attribute the controller serialises.
    // Falls back to the standard European order if the attribute is missing
    // (defensive — a bad parse shouldn't break the page).
    var wheelOrder = parseWheelOrder(els.main.dataset.wheelOrder) ||
        [0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23,
         10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26];
    var POCKETS = wheelOrder.length;
    var SLICE_DEG = 360 / POCKETS;
    // Standard European red pockets — matches Roulette.RED_NUMBERS server-
    // side. Used for colouring SVG slices and the centre "Last" pill.
    var RED_NUMBERS = new Set([
        1, 3, 5, 7, 9, 12, 14, 16, 18,
        19, 21, 23, 25, 27, 30, 32, 34, 36,
    ]);

    renderWheel(wheel, wheelOrder);
    bindBetChips();
    bindStraightInput();

    // ─── Spin animation state ────────────────────────────────────────────

    var rafId = null;
    var rotation = 0;
    var spinStartedAt = 0;
    var tickIntervalId = null;
    var settleTimeoutIds = [];

    function startSpinAnimation() {
        // Strip any prior "settling" class so a fresh spin doesn't inherit
        // the previous round's transition timing curve.
        wheel.classList.remove('settling');
        spinStartedAt = performance.now();
        cancelAllTimers();

        if (REDUCED_MOTION) {
            // Skip the live animation; we'll snap straight to the settle
            // angle in stopSpinAnimation. Keep a ticking sound for feedback.
            if (window.CasinoSounds) window.CasinoSounds.play('deal');
            return null;
        }

        function frame(now) {
            var elapsed = (now - spinStartedAt) / 1000;
            rotation = (rotation + SPIN_DPS * (ANIMATION_FRAME_MS / 1000)) % 360;
            wheel.style.transform = 'rotate(' + rotation + 'deg)';
            spinStartedAt = now;
            rafId = requestAnimationFrame(frame);
        }
        rafId = requestAnimationFrame(frame);

        // Constant-cadence ticks while the wheel is at full speed. Stops
        // when the response lands and we hand off to the settle ticks.
        if (window.CasinoSounds) {
            window.CasinoSounds.play('deal');
            tickIntervalId = setInterval(function () {
                window.CasinoSounds.play('tick');
            }, 70);
        }
        return rafId;
    }

    function stopSpinAnimation(_handle, body) {
        if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
        if (tickIntervalId) { clearInterval(tickIntervalId); tickIntervalId = null; }

        if (!body || typeof body.landed !== 'number') {
            // Network / error path — leave the wheel where it is and skip
            // the settle. The toast surfaces the error to the player.
            return;
        }

        var idx = wheelOrder.indexOf(body.landed);
        if (idx < 0) return;

        // Pocket angle relative to the pointer at top. Wheel rotates
        // clockwise, so target rotation needs the pocket index travelling
        // counter-clockwise to align with the pointer.
        var pocketAngle = -idx * SLICE_DEG;

        // Build a target rotation > current rotation that ends at
        // pocketAngle (mod 360), with a couple of extra full turns so the
        // settle reads as "decelerating" rather than "snapping".
        var current = rotation;
        var extraTurns = REDUCED_MOTION ? 0 : 2;
        var settleDeg = current + extraTurns * 360;
        // Normalise current heading and add the offset to land on pocketAngle.
        var currentHeading = ((current % 360) + 360) % 360;
        var targetHeading = ((pocketAngle % 360) + 360) % 360;
        var delta = targetHeading - currentHeading;
        if (delta < 0) delta += 360;
        settleDeg += delta;

        rotation = settleDeg;
        if (REDUCED_MOTION) {
            // Snap to the final angle without a transition.
            wheel.style.transform = 'rotate(' + settleDeg + 'deg)';
            paintLanded(body);
            playOutcomeCues(body);
            return;
        }

        wheel.classList.add('settling');
        // Force a layout flush so the browser registers the transition
        // start point before we change the transform.
        // eslint-disable-next-line no-unused-expressions
        wheel.offsetHeight;
        wheel.style.transform = 'rotate(' + settleDeg + 'deg)';

        // Decelerating tick cues during the settle — quadratic falloff so
        // the cadence audibly slows as the ball approaches its pocket.
        scheduleSettleTicks(SETTLE_MS, 14);
        // Finish: clean up the transition class, paint the last-landed
        // pill, and hand off to the win/lose cue.
        var finishId = setTimeout(function () {
            wheel.classList.remove('settling');
            paintLanded(body);
            playOutcomeCues(body);
        }, SETTLE_MS + 30);
        settleTimeoutIds.push(finishId);
    }

    function scheduleSettleTicks(durationMs, count) {
        for (var i = 1; i <= count; i++) {
            var t = Math.round(durationMs * (1 - Math.pow(1 - i / count, 2.4)));
            (function (delay) {
                var id = setTimeout(function () {
                    if (window.CasinoSounds) window.CasinoSounds.play('tick');
                }, delay);
                settleTimeoutIds.push(id);
            })(t);
        }
    }

    function playOutcomeCues(body) {
        if (!window.CasinoSounds) return;
        window.CasinoSounds.play('ball');
        var win = body.net > 0;
        // Slight delay so the ball-drop cue doesn't overlap the win/lose
        // arpeggio — keeps the audio readable.
        var id = setTimeout(function () {
            window.CasinoSounds.play(win ? 'win' : 'lose');
        }, 160);
        settleTimeoutIds.push(id);
    }

    function paintLanded(body) {
        if (!landedEl) return;
        landedEl.textContent = body.landed;
        landedEl.classList.remove('color-red', 'color-black', 'color-green');
        var colorClass = 'color-' + (body.color ? body.color.toLowerCase() : 'green');
        landedEl.classList.add(colorClass);
    }

    function cancelAllTimers() {
        if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
        if (tickIntervalId) { clearInterval(tickIntervalId); tickIntervalId = null; }
        settleTimeoutIds.forEach(function (id) { clearTimeout(id); });
        settleTimeoutIds = [];
    }

    // ─── Bet selection ──────────────────────────────────────────────────

    function bindBetChips() {
        var chips = fieldset.querySelectorAll('.roulette-chip');
        chips.forEach(function (chip) {
            chip.addEventListener('click', function () { selectChip(chip); });
            chip.addEventListener('keydown', function (e) {
                if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); selectChip(chip); }
            });
        });
        // Default to the first chip so a player can hit Spin immediately.
        if (chips.length) selectChip(chips[0]);
    }

    function selectChip(chip) {
        var chips = fieldset.querySelectorAll('.roulette-chip');
        chips.forEach(function (c) { c.setAttribute('aria-checked', 'false'); });
        chip.setAttribute('aria-checked', 'true');
        var requiresNumber = chip.dataset.requiresNumber === 'true';
        if (straightPicker) straightPicker.hidden = !requiresNumber;
        if (window.CasinoSounds) window.CasinoSounds.play('click');
    }

    function bindStraightInput() {
        if (!straightInput) return;
        straightInput.addEventListener('input', function () {
            // Clamp into [0, 36] without disrupting mid-typing — only
            // after blur so a user can clear the field while editing.
        });
        straightInput.addEventListener('blur', function () {
            var v = parseInt(straightInput.value, 10);
            if (isNaN(v)) v = 0;
            v = Math.max(0, Math.min(36, v));
            straightInput.value = v;
        });
    }

    function selectedBet() {
        var checked = fieldset.querySelector('.roulette-chip[aria-checked="true"]');
        return checked ? checked.dataset.bet : null;
    }

    function selectedNumber() {
        if (!straightInput || straightPicker.hidden) return null;
        var v = parseInt(straightInput.value, 10);
        if (isNaN(v)) return null;
        return Math.max(0, Math.min(36, v));
    }

    // ─── Game loop wiring ───────────────────────────────────────────────

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/roulette/spin',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        minSettleMs: REDUCED_MOTION ? 200 : SETTLE_MS + 200,
        failureMessage: 'Spin failed.',
        startAnimation: startSpinAnimation,
        stopAnimation: stopSpinAnimation,
        renderResult: function (body) {
            renderRouletteResult(els.resultEl, body, tableEl);
        },
        validate: function (state) {
            var bet = selectedBet();
            if (!bet) return 'Pick a bet.';
            if (bet === 'STRAIGHT' && selectedNumber() == null) {
                return 'Pick a number 0-36 for a straight bet.';
            }
            return null;
        },
        buildPayload: function (state) {
            var payload = {
                stake: state.stake,
                autoTopUp: state.autoTopUp,
                bet: selectedBet(),
            };
            if (payload.bet === 'STRAIGHT') {
                payload.number = selectedNumber();
            }
            return payload;
        },
    });

    // ─── Helpers ────────────────────────────────────────────────────────

    function parseWheelOrder(raw) {
        if (!raw) return null;
        var parts = raw.split(',').map(function (s) { return parseInt(s.trim(), 10); });
        if (parts.some(isNaN) || parts.length !== 37) return null;
        return parts;
    }

    function renderWheel(svg, order) {
        var SVG_NS = 'http://www.w3.org/2000/svg';
        // Clear any pre-existing children (e.g. on hot reload during dev).
        while (svg.firstChild) svg.removeChild(svg.firstChild);

        // Outer rim circle.
        var rim = document.createElementNS(SVG_NS, 'circle');
        rim.setAttribute('cx', '0'); rim.setAttribute('cy', '0');
        rim.setAttribute('r', '100');
        rim.setAttribute('class', 'roulette-wheel-rim');
        svg.appendChild(rim);

        // 37 pocket sectors as donut slices between r=64 and r=100.
        order.forEach(function (pocket, i) {
            var startAngle = i * SLICE_DEG - 90 - SLICE_DEG / 2;
            var endAngle   = startAngle + SLICE_DEG;
            var path = document.createElementNS(SVG_NS, 'path');
            path.setAttribute('d', donutSlicePath(64, 100, startAngle, endAngle));
            var color = pocket === 0 ? 'green' :
                (RED_NUMBERS.has(pocket) ? 'red' : 'black');
            path.setAttribute('class',
                'roulette-wheel-pocket-' + color + ' roulette-wheel-pocket-stroke');
            svg.appendChild(path);

            // Number label, positioned mid-slice on a smaller radius so
            // it sits inside the coloured ring.
            var midAngle = i * SLICE_DEG - 90;
            var lr = 82;
            var lx = lr * Math.cos(deg2rad(midAngle));
            var ly = lr * Math.sin(deg2rad(midAngle));
            var label = document.createElementNS(SVG_NS, 'text');
            label.setAttribute('x', lx); label.setAttribute('y', ly);
            label.setAttribute('transform',
                'rotate(' + (midAngle + 90) + ' ' + lx + ' ' + ly + ')');
            label.setAttribute('class', 'roulette-wheel-label');
            label.textContent = String(pocket);
            svg.appendChild(label);
        });

        // Centre hub.
        var hub = document.createElementNS(SVG_NS, 'circle');
        hub.setAttribute('cx', '0'); hub.setAttribute('cy', '0');
        hub.setAttribute('r', '52');
        hub.setAttribute('class', 'roulette-wheel-hub');
        svg.appendChild(hub);
    }

    function donutSlicePath(rInner, rOuter, startDeg, endDeg) {
        var s = deg2rad(startDeg);
        var e = deg2rad(endDeg);
        var x1 = rOuter * Math.cos(s), y1 = rOuter * Math.sin(s);
        var x2 = rOuter * Math.cos(e), y2 = rOuter * Math.sin(e);
        var x3 = rInner * Math.cos(e), y3 = rInner * Math.sin(e);
        var x4 = rInner * Math.cos(s), y4 = rInner * Math.sin(s);
        var largeArc = (endDeg - startDeg) > 180 ? 1 : 0;
        return [
            'M', x1, y1,
            'A', rOuter, rOuter, 0, largeArc, 1, x2, y2,
            'L', x3, y3,
            'A', rInner, rInner, 0, largeArc, 0, x4, y4,
            'Z',
        ].join(' ');
    }

    function deg2rad(d) { return d * Math.PI / 180; }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderRouletteResult };
}
