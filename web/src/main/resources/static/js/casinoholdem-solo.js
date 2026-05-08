(function () {
    "use strict";

    // Shared card-glyph rendering — the same path blackjack-solo uses.
    // Falls back to inline glyphs if casino-render didn't load.
    function renderCards(container, cards) {
        if (window.CasinoRender) {
            window.CasinoRender.renderCards(container, cards);
            return;
        }
        container.innerHTML = "";
        (cards || []).forEach(function (c) {
            var el = document.createElement("span");
            el.className = "casino-card-glyph";
            if (c === "??") {
                el.classList.add("che-card-hidden");
                el.textContent = "🂠";
            } else {
                el.textContent = c;
                if (c.indexOf("♥") >= 0 || c.indexOf("♦") >= 0) {
                    el.classList.add("casino-card-red");
                }
            }
            container.appendChild(el);
        });
    }

    // Dedup the celebratory chip-stack flourish per resolved hand. Each
    // /casinoholdem deal mints a fresh tableId, so keying on tableId is
    // sufficient — the resolved poll arrives multiple times before the
    // user clicks Deal again.
    function createFlashDedup() {
        var lastKey = null;
        return function shouldFlash(state) {
            if (!state || !state.lastResult) return false;
            var key = String(state.tableId);
            if (key === lastKey) return false;
            lastKey = key;
            return true;
        };
    }

    var main = document.getElementById("main");
    if (!main) return;

    var guildId = main.dataset.guildId;
    var callMultiple = parseInt(main.dataset.callMultiple || "2", 10);

    var dealForm = document.getElementById("che-deal");
    var stakeInput = document.getElementById("che-stake");
    var balanceEl = document.getElementById("che-balance");
    var tableEl = document.getElementById("che-table");
    var dealerCardsEl = document.getElementById("che-dealer-cards");
    var boardCardsEl = document.getElementById("che-board-cards");
    var playerCardsEl = document.getElementById("che-player-cards");
    var callBtn = document.getElementById("che-action-call");
    var foldBtn = document.getElementById("che-action-fold");
    var callCostEl = document.getElementById("che-call-cost");
    var resultEl = document.getElementById("che-result");
    var playerRowEl = document.getElementById("che-player-row");

    var pollTimer = null;
    var shouldFlash = createFlashDedup();

    function setActionsEnabled(enabled) {
        callBtn.disabled = !enabled;
        foldBtn.disabled = !enabled;
    }

    function renderState(state) {
        if (!state) return;
        tableEl.hidden = false;
        renderCards(dealerCardsEl, state.dealerHole);
        renderCards(boardCardsEl, state.board);
        renderCards(playerCardsEl, state.playerHole);
        callCostEl.textContent = state.callStake;

        if (state.phase === "AWAIT_DECISION") {
            setActionsEnabled(true);
            resultEl.textContent = "";
            resultEl.className = "che-result muted";
            if (playerRowEl) playerRowEl.classList.add("is-active");
        } else {
            setActionsEnabled(false);
            if (playerRowEl) playerRowEl.classList.remove("is-active");
            if (state.phase === "RESOLVED" && state.lastResult) {
                renderResult(state);
                stopPoll();
            }
        }
    }

    function renderResult(state) {
        var r = state.lastResult;
        var label;
        var cls = "muted";
        if (r.folded) {
            label = "Folded — ante of " + r.anteStake + " forfeited.";
            cls = "che-lose";
        } else if (r.net > 0) {
            label = winLabel(r) + " Net +" + r.net + " credits.";
            cls = "che-win";
        } else if (r.net < 0) {
            label = "Dealer wins. Net " + r.net + " credits.";
            cls = "che-lose";
        } else {
            label = "Push.";
        }
        resultEl.textContent = label;
        resultEl.className = "che-result " + cls;

        if (shouldFlash(state) && window.CasinoRender) {
            if (r.totalPayout && r.totalPayout > 0 && playerRowEl) {
                window.CasinoRender.flashChipsOn(playerRowEl, r.totalPayout);
            }
            if (window.CasinoSounds) {
                window.CasinoSounds.play(r.net > 0 ? "win" : "lose");
            }
        }
    }

    function winLabel(r) {
        switch (r.callResult) {
            case "WIN_ROYAL_FLUSH": return "Royal flush! Paid 100:1.";
            case "WIN_STRAIGHT_FLUSH": return "Straight flush — paid 20:1.";
            case "WIN_QUADS": return "Four of a kind — paid 10:1.";
            case "WIN_FULL_HOUSE": return "Full house — paid 3:1.";
            case "WIN_FLUSH": return "Flush — paid 2:1.";
            case "WIN_STRAIGHT": return "Straight — paid 1:1.";
            case "WIN_OTHER": return "You win.";
            case "PUSH":
                return r.dealerQualified
                    ? "Tied with the dealer."
                    : "Dealer didn't qualify — ante pays even, call pushes.";
            default: return "You win.";
        }
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
        return fetch("/casinoholdem/" + guildId + "/state", { credentials: "same-origin" })
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

    dealForm.addEventListener("submit", function (e) {
        e.preventDefault();
        var stake = parseInt(stakeInput.value, 10);
        if (!stake) return;
        window.TobyApi.postJson("/casinoholdem/" + guildId + "/deal", { stake: stake })
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Deal failed.");
                    return;
                }
                window.TobyBalance.update(balanceEl, b.newBalance);
                refreshState();
                startPoll();
            });
    });

    function postAction(action) {
        return window.TobyApi.postJson("/casinoholdem/" + guildId + "/action", { action: action })
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Action failed.");
                    return;
                }
                if (typeof b.newBalance === "number") {
                    window.TobyBalance.update(balanceEl, b.newBalance);
                }
                refreshState();
            });
    }

    callBtn.addEventListener("click", function () { postAction("call"); });
    foldBtn.addEventListener("click", function () { postAction("fold"); });

    // On page load, see if the player already has an in-flight hand.
    refreshState().then(function () {
        if (!callBtn.disabled || !foldBtn.disabled) startPoll();
    });
})();
