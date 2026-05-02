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
    var startBtn = document.getElementById("bj-action-start");
    var leaveBtn = document.getElementById("bj-action-leave");
    var statusEl = document.getElementById("bj-status");
    var resultEl = document.getElementById("bj-result");
    var joinCard = document.getElementById("bj-join-card");
    var joinBtn = document.getElementById("bj-join");

    var pollTimer = null;
    var clockTimer = null;
    var deadlineMs = null;

    function renderCards(container, cards) {
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
            var div = document.createElement("div");
            div.className = "bj-seat";
            if (idx === state.actorIndex && state.phase === "PLAYER_TURNS") {
                div.classList.add("bj-seat-active");
            }
            if (seat.status === "BUSTED") div.classList.add("bj-seat-busted");
            var header = document.createElement("div");
            header.className = "bj-seat-header";
            header.innerHTML = "<span>" + (seat.discordId === parseInt(myId, 10) ? "You" : "@" + seat.discordId) +
                " — stake " + seat.stake + (seat.doubled ? " (doubled)" : "") + "</span>" +
                "<span class=\"bj-seat-status\">(" + seat.total + ") " + statusBadge(seat.status, seat.pendingLeave) + "</span>";
            div.appendChild(header);
            var cards = document.createElement("div");
            cards.className = "bj-cards";
            renderCards(cards, seat.hand);
            div.appendChild(cards);
            seatsEl.appendChild(div);
        });
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
            ? (actorSeat.discordId === parseInt(myId, 10) ? "You" : "@" + actorSeat.discordId)
            : "—";

        var seated = state.mySeatIndex != null;
        joinCard.hidden = seated || state.phase !== "LOBBY";

        var iAmHost = state.hostDiscordId != null && state.hostDiscordId === parseInt(myId, 10);
        startBtn.hidden = !(iAmHost && state.phase === "LOBBY");
        leaveBtn.hidden = !seated;

        if (state.isMyTurn) {
            hitBtn.disabled = false;
            standBtn.disabled = false;
            doubleBtn.disabled = !state.canDouble;
        } else {
            hitBtn.disabled = true;
            standBtn.disabled = true;
            doubleBtn.disabled = true;
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
            var who = parseInt(id, 10) === parseInt(myId, 10) ? "You" : "@" + id;
            lines.push(who + ": " + label + payout);
        });
        resultEl.textContent = "Hand #" + r.handNumber + " — " + lines.join(" · ") +
            ". Pot " + r.pot + " (rake " + r.rake + " → jackpot).";
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

    function postAction(action) {
        return fetch("/blackjack/" + guildId + "/" + tableId + "/action", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ action: action })
        }).then(function (r) { return r.json().then(function (b) { return { r: r, b: b }; }); })
          .then(function (rb) {
              if (!rb.r.ok || !rb.b.ok) {
                  window.toasts && window.toasts.error(rb.b.error || "Action failed.");
                  return;
              }
              refreshState();
          });
    }

    hitBtn.addEventListener("click", function () { postAction("hit"); });
    standBtn.addEventListener("click", function () { postAction("stand"); });
    doubleBtn.addEventListener("click", function () { postAction("double"); });

    startBtn.addEventListener("click", function () {
        fetch("/blackjack/" + guildId + "/" + tableId + "/start", {
            method: "POST", credentials: "same-origin"
        }).then(function (r) { return r.json().then(function (b) { return { r: r, b: b }; }); })
          .then(function (rb) {
              if (!rb.r.ok || !rb.b.ok) {
                  window.toasts && window.toasts.error(rb.b.error || "Start failed.");
                  return;
              }
              refreshState();
          });
    });

    joinBtn.addEventListener("click", function () {
        fetch("/blackjack/" + guildId + "/" + tableId + "/join", {
            method: "POST", credentials: "same-origin"
        }).then(function (r) { return r.json().then(function (b) { return { r: r, b: b }; }); })
          .then(function (rb) {
              if (!rb.r.ok || !rb.b.ok) {
                  window.toasts && window.toasts.error(rb.b.error || "Join failed.");
                  return;
              }
              refreshState();
          });
    });

    leaveBtn.addEventListener("click", function () {
        fetch("/blackjack/" + guildId + "/" + tableId + "/leave", {
            method: "POST", credentials: "same-origin"
        }).then(function (r) { return r.json().then(function (b) { return { r: r, b: b }; }); })
          .then(function (rb) {
              if (!rb.r.ok || !rb.b.ok) {
                  window.toasts && window.toasts.error(rb.b.error || "Leave failed.");
                  return;
              }
              if (rb.b.queued) {
                  window.toasts && window.toasts.info("Leaving — you'll auto-stand and be removed at end of hand.");
              } else {
                  window.toasts && window.toasts.info("Cashed out " + rb.b.refund + " credits.");
                  setTimeout(function () { window.location.href = "/blackjack/" + guildId; }, 800);
              }
              refreshState();
          });
    });

    refreshState();
    startPoll();
})();
