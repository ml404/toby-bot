(function () {
    "use strict";

    var main = document.getElementById("main");
    var guildId = main.dataset.guildId;

    var dealForm = document.getElementById("bj-deal");
    var stakeInput = document.getElementById("bj-stake");
    var balanceEl = document.getElementById("bj-balance");
    var tableEl = document.getElementById("bj-table");
    var dealerCardsEl = document.getElementById("bj-dealer-cards");
    var dealerTotalEl = document.getElementById("bj-dealer-total");
    var playerCardsEl = document.getElementById("bj-player-cards");
    var playerTotalEl = document.getElementById("bj-player-total");
    var hitBtn = document.getElementById("bj-action-hit");
    var standBtn = document.getElementById("bj-action-stand");
    var doubleBtn = document.getElementById("bj-action-double");
    var resultEl = document.getElementById("bj-result");

    var pollTimer = null;

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

    function renderState(state) {
        if (!state) return;
        tableEl.hidden = false;
        renderCards(dealerCardsEl, state.dealer);
        dealerTotalEl.textContent = state.dealer && state.dealer.length
            ? "(" + state.dealerTotalVisible + ")" : "";

        var seat = state.seats && state.seats.length ? state.seats[0] : null;
        renderCards(playerCardsEl, seat ? seat.hand : []);
        playerTotalEl.textContent = seat ? "(" + seat.total + ")" : "";

        if (state.phase === "PLAYER_TURNS" && state.isMyTurn) {
            hitBtn.disabled = false;
            standBtn.disabled = false;
            doubleBtn.disabled = !state.canDouble;
            resultEl.textContent = "";
            resultEl.className = "bj-result muted";
        } else {
            hitBtn.disabled = true;
            standBtn.disabled = true;
            doubleBtn.disabled = true;
        }

        if (state.lastResult && state.phase === "RESOLVED") {
            renderResult(state, seat);
            stopPoll();
        }
    }

    function renderResult(state, seat) {
        var r = state.lastResult;
        var seatResult = (r.seatResults || {})[seat.discordId.toString()];
        var label;
        var cls = "muted";
        switch (seatResult) {
            case "PLAYER_BLACKJACK":
                label = "Blackjack! Paid 3:2.";
                cls = "bj-win";
                break;
            case "PLAYER_WIN":
                label = "You win.";
                cls = "bj-win";
                break;
            case "PUSH":
                label = "Push — stake refunded.";
                break;
            case "DEALER_WIN":
                label = "Dealer wins.";
                cls = "bj-lose";
                break;
            case "PLAYER_BUST":
                label = "Bust.";
                cls = "bj-lose";
                break;
            default:
                label = "";
        }
        resultEl.textContent = label;
        resultEl.className = "bj-result " + cls;
    }

    function startPoll() {
        stopPoll();
        pollTimer = setInterval(refreshState, 1500);
    }

    function stopPoll() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function refreshState() {
        return fetch("/blackjack/" + guildId + "/solo/state", { credentials: "same-origin" })
            .then(function (r) {
                if (r.status === 404) {
                    stopPoll();
                    return null;
                }
                if (!r.ok) throw new Error("state HTTP " + r.status);
                return r.json();
            })
            .then(function (state) { renderState(state); })
            .catch(function (e) { console.warn("state poll failed", e); });
    }

    function setBalance(b) {
        if (b == null) return;
        balanceEl.textContent = b;
    }

    dealForm.addEventListener("submit", function (e) {
        e.preventDefault();
        var stake = parseInt(stakeInput.value, 10);
        if (!stake) return;
        // POSTs go via TobyApi.postJson so the Spring Security CSRF
        // header (read off the <meta name="_csrf"> tag in the head
        // fragment) is included — without it Spring rejects the request
        // with 403.
        window.TobyApi.postJson("/blackjack/" + guildId + "/solo/deal", { stake: stake })
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Deal failed.");
                    return;
                }
                if (b.resolved) {
                    setBalance(b.newBalance);
                    refreshState();
                } else {
                    refreshState();
                    startPoll();
                }
            });
    });

    function postAction(action) {
        return window.TobyApi.postJson("/blackjack/" + guildId + "/solo/action", { action: action })
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Action failed.");
                    return;
                }
                if (b.resolved) {
                    setBalance(b.newBalance);
                }
                refreshState();
            });
    }

    hitBtn.addEventListener("click", function () { postAction("hit"); });
    standBtn.addEventListener("click", function () { postAction("stand"); });
    doubleBtn.addEventListener("click", function () { postAction("double"); });

    // On page load, see if there's an existing in-flight hand for this user.
    refreshState().then(function () {
        if (!hitBtn.disabled || !standBtn.disabled) startPoll();
    });
})();
