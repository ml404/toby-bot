// Shared "casino table" render primitives. Both /blackjack and /poker call
// into this module so the avatar/name/seat/card/actor-pill rendering is one
// implementation in one place. Loaded via <script> before the per-game JS.
//
// Public API on `window.CasinoRender`:
//   renderCards(container, cards)             — paint cards as glyphs;
//                                                animates only freshly arrived ones
//   makeAvatar(seat)                          — <img> or fallback initial-chip
//   makeSeatHeader(seat, opts)                — DOM <div> with avatar + name + meta
//   renderActorPill(pillEl, state, opts)      — toggle + populate the gold pill
//
// Game-specific bits (status badges, stake formatting) are passed in via
// the `opts` callbacks instead of trying to express them generically.
(function () {
    "use strict";

    // Per-container "previous card count" so the deal animation only fires
    // on cards that just arrived, not on every 2-second poll re-render.
    var dealtCounts = new WeakMap();

    function renderCards(container, cards) {
        if (!container) return;
        var prev = dealtCounts.get(container) || 0;
        var list = cards || [];
        // Card count went DOWN — that's a fresh deal, not an in-place
        // update. Reset so every card animates in.
        if (list.length < prev) prev = 0;
        container.innerHTML = "";
        var firstFreshIndex = prev;
        for (var i = 0; i < list.length; i++) {
            var c = list[i];
            var el = document.createElement("span");
            el.className = "casino-card-glyph";
            if (c === "??") {
                el.classList.add("is-hidden");
                el.textContent = "🂠";
            } else {
                el.textContent = c;
                if (c.indexOf("♥") >= 0 || c.indexOf("♦") >= 0) {
                    el.classList.add("is-red");
                }
            }
            // Only animate cards that weren't there last render. Stagger
            // each new card by 90ms so a 2-card initial deal fans in
            // instead of arriving simultaneously.
            if (i >= prev) {
                el.classList.add("is-dealt");
                el.style.animationDelay = ((i - firstFreshIndex) * 90) + "ms";
            }
            container.appendChild(el);
        }
        // Notify listeners (e.g. sounds module) about how many cards
        // just landed. Stagger the deal cue once per arriving card so
        // the click matches the visual.
        var freshCount = Math.max(0, list.length - firstFreshIndex);
        if (freshCount > 0 && window.CasinoSounds) {
            for (var k = 0; k < freshCount; k++) {
                (function (idx) {
                    setTimeout(function () { window.CasinoSounds.play("deal"); }, idx * 90);
                })(k);
            }
        }
        dealtCounts.set(container, list.length);
    }

    function initialOf(name) {
        if (!name) return "?";
        var trimmed = String(name).trim();
        return trimmed.length > 0 ? trimmed.charAt(0).toUpperCase() : "?";
    }

    function makeAvatar(seat) {
        if (seat && seat.avatarUrl) {
            var img = document.createElement("img");
            img.className = "casino-avatar";
            img.src = seat.avatarUrl;
            img.alt = "";
            img.loading = "lazy";
            return img;
        }
        var span = document.createElement("span");
        span.className = "casino-avatar is-fallback";
        span.dataset.initial = initialOf(seat && seat.displayName);
        return span;
    }

    // Build a seat header DOM. opts.isMe forces the "You" label; opts.metaText
    // is the right-aligned bit (e.g. "stake 100 (doubled)" or "1500 chips · all-in").
    function makeSeatHeader(seat, opts) {
        opts = opts || {};
        var wrap = document.createElement("div");
        wrap.className = "casino-seat-header";

        var who = document.createElement("div");
        who.className = "casino-seat-who";
        who.appendChild(makeAvatar(seat));

        var name = document.createElement("span");
        name.className = "casino-seat-name";
        name.textContent = opts.isMe ? "You" : (seat.displayName || ("Player " + String(seat.discordId).slice(-4)));
        who.appendChild(name);
        wrap.appendChild(who);

        if (opts.metaText) {
            var meta = document.createElement("span");
            meta.className = "casino-seat-meta";
            meta.textContent = opts.metaText;
            wrap.appendChild(meta);
        }
        return wrap;
    }

    // Toggle and populate the actor pill at the top of the felt. Hidden when
    // there's no acting seat (lobby / dealer turn / resolved).
    //
    // opts.label    — text for the small uppercase tag, default "Acting"
    // opts.clockText — optional extra "12s" countdown text appended on the right
    function renderActorPill(pillEl, actorSeat, opts) {
        if (!pillEl) return;
        if (!actorSeat) {
            pillEl.hidden = true;
            pillEl.innerHTML = "";
            return;
        }
        opts = opts || {};
        pillEl.hidden = false;
        pillEl.innerHTML = "";
        pillEl.appendChild(makeAvatar(actorSeat));

        var label = document.createElement("span");
        label.className = "casino-actor-pill-label";
        label.textContent = opts.label || "Acting";
        pillEl.appendChild(label);

        var name = document.createElement("span");
        name.className = "casino-actor-pill-name";
        name.textContent = actorSeat.displayName ||
            ("Player " + String(actorSeat.discordId).slice(-4));
        pillEl.appendChild(name);

        if (opts.clockText) {
            var clock = document.createElement("span");
            clock.className = "casino-actor-pill-clock";
            clock.textContent = opts.clockText;
            pillEl.appendChild(clock);
        }
    }

    // Drop a small celebratory chip stack onto a seat element. Used by the
    // game JS the moment a hand resolves with a positive payout — the stack
    // animates in (one chip popping every 80ms), the payout label floats up,
    // and the whole thing removes itself after ~1.6s so it doesn't pile up
    // on subsequent polls.
    function flashChipsOn(seatEl, payoutAmount, chipCount) {
        if (!seatEl || !payoutAmount || payoutAmount <= 0) return;
        // De-dupe: if a previous flash for the same hand is still on screen,
        // strip it before starting a new one.
        var existing = seatEl.querySelector(".casino-chip-stack");
        if (existing) existing.remove();

        var stack = document.createElement("div");
        stack.className = "casino-chip-stack";

        var label = document.createElement("div");
        label.className = "casino-chip-payout";
        label.textContent = "+" + payoutAmount;
        stack.appendChild(label);

        // Cap chip count so a "+10000" payout doesn't draw 100 chips.
        var n = Math.max(1, Math.min(chipCount || 5, 7));
        for (var i = 0; i < n; i++) {
            var chip = document.createElement("span");
            chip.className = "casino-chip";
            chip.style.animationDelay = (i * 80) + "ms";
            stack.appendChild(chip);
            // Synchronise the audible chip-clink with each chip's pop —
            // gives the animation real weight.
            if (window.CasinoSounds) {
                (function (delay) {
                    setTimeout(function () { window.CasinoSounds.play("chip"); }, delay);
                })(i * 80);
            }
        }
        seatEl.appendChild(stack);

        // Clean up after the longest animation (payout-float = 1.6s).
        setTimeout(function () { if (stack.parentNode) stack.remove(); }, 1700);
    }

    window.CasinoRender = {
        renderCards: renderCards,
        makeAvatar: makeAvatar,
        makeSeatHeader: makeSeatHeader,
        renderActorPill: renderActorPill,
        flashChipsOn: flashChipsOn,
    };
})();
