(function () {
    "use strict";

    // Shared with the rest of the casino card games so every felt
    // beats out at the same pace.
    var DEALER_REVEAL_STAGGER_MS = (window.CasinoRender && window.CasinoRender.DEALER_REVEAL_STAGGER_MS) || 400;

    function errorToast(msg) {
        if (typeof window.toast === "function") window.toast(msg, "error");
    }

    // Shared card-glyph rendering — the same path blackjack-solo uses.
    // Falls back to inline glyphs if casino-render didn't load.
    function renderCards(container, cards, opts) {
        if (window.CasinoRender) {
            return window.CasinoRender.renderCards(container, cards, opts);
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
        return { freshCount: 0, settleMs: 0 };
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
    var dealBusy = false;
    var actionBusy = false;
    var shouldFlash = createFlashDedup();
    var resultDefer = (window.TobyBlackjackSolo && window.TobyBlackjackSolo.createDeferredScheduler)
        ? window.TobyBlackjackSolo.createDeferredScheduler()
        : (function () {
            var t = null;
            return {
                schedule: function (ms, fn) {
                    if (t) { clearTimeout(t); t = null; }
                    if (!ms || ms <= 0) { fn(); return; }
                    t = setTimeout(function () { t = null; fn(); }, ms);
                },
                cancel: function () { if (t) { clearTimeout(t); t = null; } },
            };
        }());

    function setActionsEnabled(enabled) {
        callBtn.disabled = !enabled;
        foldBtn.disabled = !enabled;
    }

    function renderState(state) {
        if (!state) return;
        tableEl.hidden = false;
        // Dealer's hole card carries the same 400ms reveal stagger as
        // every other card game so the felt feels consistent across
        // /blackjack, /casinoholdem and /baccarat.
        var dealerDeal = renderCards(dealerCardsEl, state.dealerHole, { staggerMs: DEALER_REVEAL_STAGGER_MS }) || { settleMs: 0 };
        renderCards(boardCardsEl, state.board);
        renderCards(playerCardsEl, state.playerHole);
        callCostEl.textContent = state.callStake;

        if (state.phase === "AWAIT_DECISION") {
            setActionsEnabled(true);
            resultDefer.cancel();
            resultEl.textContent = "";
            resultEl.className = "che-result muted";
            if (playerRowEl) playerRowEl.classList.add("is-active");
        } else {
            setActionsEnabled(false);
            if (playerRowEl) playerRowEl.classList.remove("is-active");
            if (state.phase === "RESOLVED" && state.lastResult) {
                // Hold the result line + chip flourish until the
                // dealer's hole card flip lands. settleMs is 0 on
                // re-renders where nothing fresh arrived.
                resultDefer.schedule(dealerDeal.settleMs, function () { renderResult(state); });
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

        // Win/lose cue + chip flourish via the shared helper. Push (net
        // == 0) is suppressed by the helper's body.push branch below
        // — set it explicitly so a tied hand stays silent.
        if (shouldFlash(state) && window.TobyCasinoWinSettle) {
            window.TobyCasinoWinSettle.fire({
                win: r.net > 0,
                net: r.net,
                push: r.net === 0 && !r.folded,
                jackpotPayout: r.jackpotPayout,
            }, playerRowEl);
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
        return fetch("/casino/" + guildId + "/casinoholdem/state", { credentials: "same-origin" })
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
        // Silent no-op while a deal is mid-flight — the Deal button is
        // disabled, but a held submit can still fire here. No spam toast:
        // the disabled control is the feedback.
        if (dealBusy) return;
        var stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            errorToast("Enter a positive stake.");
            return;
        }
        // Click cue on bet submit so casinoholdem has the same audio
        // anchor as the rest of the minigames at "round started".
        if (window.CasinoSounds) window.CasinoSounds.play("click");
        resultDefer.cancel();
        dealBusy = true;
        window.TobyApi.postJson("/casino/" + guildId + "/casinoholdem/deal", { stake: stake })
            .then(function (b) {
                if (!b.ok) {
                    errorToast(b.error || "Deal failed.");
                    return;
                }
                window.TobyBalance.update(balanceEl, b.newBalance);
                refreshState();
                startPoll();
            })
            .catch(function () { errorToast("Network error."); })
            .then(function () { dealBusy = false; });
    });

    function postAction(action) {
        if (actionBusy) return Promise.resolve();
        actionBusy = true;
        setActionsEnabled(false);
        return window.TobyApi.postJson("/casino/" + guildId + "/casinoholdem/action", { action: action })
            .then(function (b) {
                if (!b.ok) {
                    errorToast(b.error || "Action failed.");
                    return;
                }
                if (typeof b.newBalance === "number") {
                    window.TobyBalance.update(balanceEl, b.newBalance);
                }
                refreshState();
            })
            .catch(function () { errorToast("Network error."); })
            .then(function () { actionBusy = false; });
    }

    callBtn.addEventListener("click", function () { postAction("call"); });
    foldBtn.addEventListener("click", function () { postAction("fold"); });

    // On page load, see if the player already has an in-flight hand.
    refreshState().then(function () {
        if (!callBtn.disabled || !foldBtn.disabled) startPoll();
    });
})();
