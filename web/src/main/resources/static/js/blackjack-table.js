(function () {
    "use strict";

    var main = document.getElementById("main");
    var guildId = main.dataset.guildId;
    var tableId = main.dataset.tableId;
    var myId = main.dataset.myDiscordId;

    var phaseEl = document.getElementById("bj-phase");
    var handNumberEl = document.getElementById("bj-hand-number");
    var toActEl = document.getElementById("bj-to-act");
    var shotClockEl = document.getElementById("bj-shot-clock");
    var dealerCardsEl = document.getElementById("bj-dealer-cards");
    var dealerTotalEl = document.getElementById("bj-dealer-total");
    var seatsEl = document.getElementById("bj-seats");
    var hitBtn = document.getElementById("bj-action-hit");
    var standBtn = document.getElementById("bj-action-stand");
    var doubleBtn = document.getElementById("bj-action-double");
    var splitBtn = document.getElementById("bj-action-split");
    var startBtn = document.getElementById("bj-action-start");
    var leaveBtn = document.getElementById("bj-action-leave");
    var statusEl = document.getElementById("bj-status");
    var resultEl = document.getElementById("bj-result");
    var joinCard = document.getElementById("bj-join-card");
    var joinBtn = document.getElementById("bj-join");

    var actorPillEl = document.getElementById("bj-actor-pill");

    var pollTimer = null;
    var clockTimer = null;
    var deadlineMs = null;
    // Tracks the handNumber of the last result we played the chip-stack
    // flourish on, so the 2s polling doesn't re-trigger the animation.
    var lastFlashedHand = null;

    function renderCards(container, cards) {
        // Delegate to the shared renderer so the per-container deal animation
        // book-keeping is consistent with poker. The shared renderer applies
        // .casino-card-glyph and the .is-dealt animation only on freshly arrived
        // cards.
        if (window.CasinoRender) {
            window.CasinoRender.renderCards(container, cards);
            return;
        }
        // Fallback (loaded out of order) — keep the legacy text-glyph render.
        container.innerHTML = "";
        (cards || []).forEach(function (c) {
            var el = document.createElement("span");
            el.className = "bj-card-glyph";
            if (c === "??") {
                el.classList.add("bj-card-hidden");
                el.textContent = "🂠";
            } else {
                el.textContent = c;
                if (c.indexOf("♥") >= 0 || c.indexOf("♦") >= 0) {
                    el.classList.add("bj-card-red");
                }
            }
            container.appendChild(el);
        });
    }

    function statusBadge(status, pendingLeave) {
        var parts = [];
        switch (status) {
            case "BLACKJACK": parts.push("Blackjack"); break;
            case "BUSTED": parts.push("Bust"); break;
            case "STANDING": parts.push("Stand"); break;
            case "DOUBLED": parts.push("Doubled"); break;
        }
        if (pendingLeave) parts.push("leaving");
        return parts.join(" · ");
    }

    function renderSeats(state) {
        seatsEl.innerHTML = "";
        (state.seats || []).forEach(function (seat, idx) {
            var slots = (seat.hands && seat.hands.length) ? seat.hands : null;
            var isActorSeat = idx === state.actorIndex && state.phase === "PLAYER_TURNS";
            var isMe = seat.discordId === myId;
            if (slots && slots.length > 1) {
                // Split seat: render the seat header once, then a sub-block per hand.
                var groupHeader = window.CasinoRender.makeSeatHeader(seat, {
                    isMe: isMe,
                    metaText: slots.length + " hands" + (seat.pendingLeave ? " · leaving" : ""),
                });
                groupHeader.dataset.discordId = String(seat.discordId);
                seatsEl.appendChild(groupHeader);
                slots.forEach(function (slot, slotIdx) {
                    var slotEl = renderSlot(seat, slot, slotIdx, isActorSeat && slotIdx === seat.activeHandIndex);
                    slotEl.dataset.discordId = String(seat.discordId);
                    seatsEl.appendChild(slotEl);
                });
                return;
            }
            // Single-hand path (back-compat with v1 seat shape).
            var div = document.createElement("div");
            div.className = "bj-seat casino-seat";
            div.dataset.discordId = String(seat.discordId);
            if (isMe) div.classList.add("is-me");
            if (isActorSeat) div.classList.add("bj-seat-active", "is-active");
            if (seat.status === "BUSTED") div.classList.add("bj-seat-busted", "is-busted");
            var meta = "(" + seat.total + ") stake " + seat.stake +
                (seat.doubled ? " (doubled)" : "") +
                (statusBadge(seat.status, seat.pendingLeave) ? " · " + statusBadge(seat.status, seat.pendingLeave) : "");
            div.appendChild(window.CasinoRender.makeSeatHeader(seat, { isMe: isMe, metaText: meta }));
            var cards = document.createElement("div");
            cards.className = "bj-cards casino-cards";
            renderCards(cards, seat.hand);
            div.appendChild(cards);
            seatsEl.appendChild(div);
        });
    }

    function renderSlot(seat, slot, slotIdx, isActiveSlot) {
        var div = document.createElement("div");
        div.className = "bj-seat casino-seat";
        if (isActiveSlot) div.classList.add("bj-seat-active", "is-active");
        if (slot.status === "BUSTED") div.classList.add("bj-seat-busted", "is-busted");
        var header = document.createElement("div");
        header.className = "bj-seat-header casino-seat-header";
        var label = document.createElement("span");
        label.className = "casino-seat-name";
        label.textContent = "  Hand " + (slotIdx + 1) +
            " — stake " + slot.stake + (slot.doubled ? " (doubled)" : "");
        header.appendChild(label);
        var meta = document.createElement("span");
        meta.className = "casino-seat-meta";
        meta.textContent = "(" + slot.total + ") " + statusBadge(slot.status, false);
        header.appendChild(meta);
        div.appendChild(header);
        var cards = document.createElement("div");
        cards.className = "bj-cards casino-cards";
        renderCards(cards, slot.cards);
        div.appendChild(cards);
        return div;
    }

    function renderState(state) {
        phaseEl.textContent = state.phase;
        handNumberEl.textContent = state.handNumber;
        renderCards(dealerCardsEl, state.dealer);
        dealerTotalEl.textContent = state.dealer && state.dealer.length
            ? "(" + state.dealerTotalVisible + ")" : "";
        renderSeats(state);

        var actorSeat = (state.seats || [])[state.actorIndex];
        toActEl.textContent = actorSeat
            ? (actorSeat.discordId === myId ? "You" : (actorSeat.displayName || ("@" + actorSeat.discordId)))
            : "—";

        // Top-of-felt actor pill — only meaningful during PLAYER_TURNS.
        var pillSeat = (state.phase === "PLAYER_TURNS") ? actorSeat : null;
        if (window.CasinoRender) {
            window.CasinoRender.renderActorPill(actorPillEl, pillSeat, {
                label: pillSeat && pillSeat.discordId === myId ? "Your turn" : "Acting",
            });
        }

        var seated = state.mySeatIndex != null;
        joinCard.hidden = seated || state.phase !== "LOBBY";

        var iAmHost = state.hostDiscordId != null && state.hostDiscordId === myId;
        startBtn.hidden = !(iAmHost && state.phase === "LOBBY");
        leaveBtn.hidden = !seated;

        if (state.isMyTurn) {
            hitBtn.disabled = false;
            standBtn.disabled = false;
            doubleBtn.disabled = !state.canDouble;
            splitBtn.disabled = !state.canSplit;
            splitBtn.hidden = !state.canSplit;
        } else {
            hitBtn.disabled = true;
            standBtn.disabled = true;
            doubleBtn.disabled = true;
            splitBtn.disabled = true;
            splitBtn.hidden = true;
        }

        if (state.lastResult && state.phase === "LOBBY") {
            renderResult(state);
        } else {
            resultEl.textContent = "";
        }

        deadlineMs = state.currentActorDeadlineEpochMillis || null;
        renderShotClock();
    }

    function renderResult(state) {
        var r = state.lastResult;
        // Build a quick id→displayName map from the live seats so the result
        // line uses real Discord names (the lastResult payload is keyed by
        // string id; the seats have the canonical name from the snapshot).
        var nameById = {};
        (state.seats || []).forEach(function (s) {
            nameById[String(s.discordId)] = s.displayName;
        });
        var lines = [];
        Object.keys(r.seatResults || {}).forEach(function (id) {
            var label;
            switch (r.seatResults[id]) {
                case "PLAYER_BLACKJACK": label = "🎉 BJ"; break;
                case "PLAYER_WIN": label = "✅ Win"; break;
                case "PUSH": label = "🤝 Push"; break;
                case "DEALER_WIN": label = "❌ Lose"; break;
                case "PLAYER_BUST": label = "💥 Bust"; break;
                default: label = r.seatResults[id];
            }
            var payout = r.payouts && r.payouts[id] ? " (+" + r.payouts[id] + ")" : "";
            var who = id === myId ? "You" : (nameById[id] || ("@" + id));
            lines.push(who + ": " + label + payout);
        });
        resultEl.textContent = "Hand #" + r.handNumber + " — " + lines.join(" · ") +
            ". Pot " + r.pot + " (rake " + r.rake + " → jackpot).";

        // Fire the celebratory chip stack once per hand. The 2s polling
        // would otherwise re-trigger every cycle while the result is on
        // screen, so we key off handNumber.
        if (r.handNumber !== lastFlashedHand && window.CasinoRender) {
            lastFlashedHand = r.handNumber;
            var myPaid = false;
            Object.keys(r.payouts || {}).forEach(function (id) {
                var amount = r.payouts[id];
                if (!amount || amount <= 0) return;
                var seatEl = seatsEl.querySelector('[data-discord-id="' + id + '"]');
                if (seatEl) window.CasinoRender.flashChipsOn(seatEl, amount);
                if (id === myId) myPaid = true;
            });
            // Sound cue keyed off the viewer's outcome — winning a hand
            // should sound like a win, even if other seats lost.
            if (window.CasinoSounds) {
                window.CasinoSounds.play(myPaid ? "win" : "lose");
            }
        }
    }

    function renderShotClock() {
        if (!deadlineMs) {
            shotClockEl.hidden = true;
            return;
        }
        var remaining = Math.max(0, Math.round((deadlineMs - Date.now()) / 1000));
        shotClockEl.hidden = false;
        shotClockEl.textContent = "Shot clock: " + remaining + "s";
    }

    function startPoll() {
        stopPoll();
        pollTimer = setInterval(refreshState, 2000);
        clockTimer = setInterval(renderShotClock, 500);
    }

    function stopPoll() {
        if (pollTimer) clearInterval(pollTimer);
        if (clockTimer) clearInterval(clockTimer);
        pollTimer = clockTimer = null;
    }

    function refreshState() {
        return fetch("/blackjack/" + guildId + "/" + tableId + "/state", { credentials: "same-origin" })
            .then(function (r) {
                if (r.status === 404) {
                    statusEl.textContent = "This table no longer exists.";
                    stopPoll();
                    return null;
                }
                if (!r.ok) throw new Error("state HTTP " + r.status);
                return r.json();
            })
            .then(function (state) { if (state) renderState(state); })
            .catch(function (e) { console.warn("state poll failed", e); });
    }

    // POSTs go via TobyApi.postJson so the Spring Security CSRF header
    // (read off the <meta name="_csrf"> tag in the head fragment) is
    // included — without it Spring rejects the request with 403.
    function postAction(action) {
        return window.TobyApi.postJson("/blackjack/" + guildId + "/" + tableId + "/action", { action: action })
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Action failed.");
                    return;
                }
                refreshState();
            });
    }

    hitBtn.addEventListener("click", function () { postAction("hit"); });
    standBtn.addEventListener("click", function () { postAction("stand"); });
    doubleBtn.addEventListener("click", function () { postAction("double"); });
    splitBtn.addEventListener("click", function () { postAction("split"); });

    startBtn.addEventListener("click", function () {
        window.TobyApi.postJson("/blackjack/" + guildId + "/" + tableId + "/start", {})
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Start failed.");
                    return;
                }
                refreshState();
            });
    });

    joinBtn.addEventListener("click", function () {
        window.TobyApi.postJson("/blackjack/" + guildId + "/" + tableId + "/join", {})
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Join failed.");
                    return;
                }
                refreshState();
            });
    });

    leaveBtn.addEventListener("click", function () {
        window.TobyApi.postJson("/blackjack/" + guildId + "/" + tableId + "/leave", {})
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Leave failed.");
                    return;
                }
                if (b.queued) {
                    window.toasts && window.toasts.info("Leaving — you'll auto-stand and be removed at end of hand.");
                } else {
                    window.toasts && window.toasts.info("Cashed out " + b.refund + " credits.");
                    setTimeout(function () { window.location.href = "/blackjack/" + guildId; }, 800);
                }
                refreshState();
            });
    });

    refreshState();
    startPoll();
})();
