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
        container.innerHTML = "";
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
            // Only animate cards that weren't there last render. When the
            // hand shrinks (new deal) we treat all of them as fresh.
            if (i >= prev) el.classList.add("is-dealt");
            container.appendChild(el);
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

    window.CasinoRender = {
        renderCards: renderCards,
        makeAvatar: makeAvatar,
        makeSeatHeader: makeSeatHeader,
        renderActorPill: renderActorPill,
    };
})();
