(function () {
    "use strict";

    // ----- DOM-agnostic helpers (defined first so jest can require the
    // module under jsdom and exercise them via `window.TobyBlackjackSolo`
    // even though the page-init code below early-returns when the
    // blackjack-solo template isn't present). -----

    function renderCards(container, cards) {
        // Delegate to the shared casino renderer so the deal animation only
        // fires on freshly arrived cards (not every poll re-render). Falls
        // back to the legacy text glyphs if the shared module didn't load.
        if (window.CasinoRender) {
            window.CasinoRender.renderCards(container, cards);
            return;
        }
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

    function renderSplitHands(container, slots, activeIndex) {
        // Reuse the per-slot DOM across polls so casino-render.js's
        // WeakMap of "cards already dealt into this container" actually
        // matches across renders. Wiping innerHTML every 1500ms made the
        // inner .bj-cards container a new DOM node each time, so the
        // freshly-arrived detection always saw `prev = 0` and re-animated
        // every card with the deal sound — the table looked and sounded
        // like cards were being dealt over and over.
        if (container.children.length !== slots.length) {
            container.innerHTML = "";
        }
        slots.forEach(function (slot, idx) {
            var div = container.children[idx] || createSplitHandSlotEl(container);
            div.className = "bj-seat casino-seat";
            if (idx === activeIndex) div.classList.add("bj-seat-active", "is-active");
            if (slot.status === "BUSTED") div.classList.add("bj-seat-busted", "is-busted");

            var label = "Hand " + (idx + 1);
            var statusBits = [];
            if (slot.doubled) statusBits.push("Doubled");
            switch (slot.status) {
                case "BLACKJACK": statusBits.push("Blackjack"); break;
                case "BUSTED": statusBits.push("Bust"); break;
                case "STANDING": statusBits.push("Stand"); break;
            }
            div.querySelector(".casino-seat-name").textContent =
                label + " — stake " + slot.stake;
            div.querySelector(".casino-seat-meta").textContent =
                "(" + slot.total + ")" + (statusBits.length ? " " + statusBits.join(" · ") : "");

            renderCards(div.querySelector(".bj-cards"), slot.cards);
        });
    }

    function createSplitHandSlotEl(container) {
        var div = document.createElement("div");
        var header = document.createElement("div");
        header.className = "bj-seat-header casino-seat-header";
        var nameEl = document.createElement("span");
        nameEl.className = "casino-seat-name";
        header.appendChild(nameEl);
        var meta = document.createElement("span");
        meta.className = "casino-seat-meta";
        header.appendChild(meta);
        div.appendChild(header);
        var cards = document.createElement("div");
        cards.className = "bj-cards casino-cards";
        div.appendChild(cards);
        container.appendChild(div);
        return div;
    }

    // Stateful dedup for the celebratory chip-stack flourish. The 1.5s
    // poll fetches the resolved state repeatedly, so we need to fire the
    // flourish once per hand. Keyed off (tableId, handNumber) — solo
    // creates a fresh BlackjackTable per hand with handNumber reset to
    // 1, so handNumber alone collides between sessions.
    function createFlashDedup() {
        var lastKey = null;
        return function shouldFlash(state) {
            if (!state || !state.lastResult) return false;
            var key = state.tableId + ":" + state.lastResult.handNumber;
            if (key === lastKey) return false;
            lastKey = key;
            return true;
        };
    }

    if (typeof window !== "undefined") {
        window.TobyBlackjackSolo = window.TobyBlackjackSolo || {};
        window.TobyBlackjackSolo.renderSplitHands = renderSplitHands;
        window.TobyBlackjackSolo.createFlashDedup = createFlashDedup;
    }

    // ----- Page init. Bail out cleanly if we're loaded without the
    // blackjack-solo template (jest tests, server error pages, etc.).

    var main = document.getElementById("main");
    if (!main) return;
    var guildId = main.dataset.guildId;

    var dealForm = document.getElementById("bj-deal");
    var stakeInput = document.getElementById("bj-stake");
    var balanceEl = document.getElementById("bj-balance");
    var tableEl = document.getElementById("bj-table");
    var dealerCardsEl = document.getElementById("bj-dealer-cards");
    var dealerTotalEl = document.getElementById("bj-dealer-total");
    var playerCardsEl = document.getElementById("bj-player-cards");
    var playerHandsEl = document.getElementById("bj-player-hands");
    var playerTotalEl = document.getElementById("bj-player-total");
    var hitBtn = document.getElementById("bj-action-hit");
    var standBtn = document.getElementById("bj-action-stand");
    var doubleBtn = document.getElementById("bj-action-double");
    var splitBtn = document.getElementById("bj-action-split");
    var resultEl = document.getElementById("bj-result");
    var playerRowEl = document.getElementById("bj-player-row");

    var pollTimer = null;
    var shouldFlash = createFlashDedup();

    function renderState(state) {
        if (!state) return;
        tableEl.hidden = false;
        renderCards(dealerCardsEl, state.dealer);
        dealerTotalEl.textContent = state.dealer && state.dealer.length
            ? "(" + state.dealerTotalVisible + ")" : "";

        var seat = state.seats && state.seats.length ? state.seats[0] : null;
        var slots = (seat && seat.hands && seat.hands.length) ? seat.hands : null;
        if (slots && slots.length > 1) {
            // Split flow: render a per-hand block, hide the single-hand
            // layout, and show the active hand's running total.
            playerCardsEl.hidden = true;
            playerHandsEl.hidden = false;
            renderSplitHands(playerHandsEl, slots, seat.activeHandIndex);
            var active = slots[seat.activeHandIndex] || slots[0];
            playerTotalEl.textContent = active ? "(active hand: " + active.total + ")" : "";
        } else {
            playerHandsEl.hidden = true;
            playerCardsEl.hidden = false;
            renderCards(playerCardsEl, seat ? seat.hand : []);
            playerTotalEl.textContent = seat ? "(" + seat.total + ")" : "";
        }

        var becomingInactive = false;
        if (state.phase === "PLAYER_TURNS" && state.isMyTurn) {
            hitBtn.disabled = false;
            standBtn.disabled = false;
            doubleBtn.disabled = !state.canDouble;
            splitBtn.hidden = !state.canSplit;
            splitBtn.disabled = !state.canSplit;
            resultEl.textContent = "";
            resultEl.className = "bj-result muted";
            if (playerRowEl) playerRowEl.classList.add("is-active");
        } else {
            hitBtn.disabled = true;
            standBtn.disabled = true;
            doubleBtn.disabled = true;
            splitBtn.disabled = true;
            splitBtn.hidden = true;
            // Defer the .is-active drop to the next frame so it doesn't tear
            // down the seat's compositing layer in the same paint as the
            // chip-stack flourish below — mobile WebKit drops the chip
            // animations otherwise. The `casino-pulse` halo lingers for one
            // extra frame, which is invisible to the eye.
            becomingInactive = !!playerRowEl &&
                playerRowEl.classList.contains("is-active");
        }

        if (state.lastResult && state.phase === "RESOLVED") {
            renderResult(state, seat);
            stopPoll();
        }

        if (becomingInactive) {
            var raf = window.requestAnimationFrame ||
                function (fn) { return setTimeout(fn, 0); };
            raf(function () { playerRowEl.classList.remove("is-active"); });
        }
    }

    function renderResult(state, seat) {
        var r = state.lastResult;
        // seat.discordId is a string from the projection so the snowflake
        // survives JS Number's 53-bit precision; lookup keys match exactly.
        var seatResult = (r.seatResults || {})[seat.discordId];
        // Fire the celebratory chip stack once per hand on a winning result.
        if (shouldFlash(state) && window.CasinoRender) {
            var payout = (r.payouts || {})[seat.discordId];
            if (payout && payout > 0 && playerRowEl) {
                window.CasinoRender.flashChipsOn(playerRowEl, payout);
            }
            if (window.CasinoSounds) {
                window.CasinoSounds.play(payout && payout > 0 ? "win" : "lose");
            }
        }
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
                // Always reflect the post-deal escrow in the wallet display, not
                // just on the natural-BJ resolved short-circuit. Without this the
                // wallet looked like it stayed full all hand and only "lost" the
                // stake on resolution — making each subsequent action feel like
                // it was charging stake again.
                setBalance(b.newBalance);
                if (b.resolved) {
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
                // Refresh on every successful action — the server echoes the
                // live wallet on Continued too. HIT/STAND don't move the
                // balance, but DOUBLE/SPLIT pre-debit additional stake mid-hand
                // and the player should see that immediately.
                setBalance(b.newBalance);
                refreshState();
            });
    }

    hitBtn.addEventListener("click", function () { postAction("hit"); });
    standBtn.addEventListener("click", function () { postAction("stand"); });
    doubleBtn.addEventListener("click", function () { postAction("double"); });
    splitBtn.addEventListener("click", function () { postAction("split"); });

    // On page load, see if there's an existing in-flight hand for this user.
    refreshState().then(function () {
        if (!hitBtn.disabled || !standBtn.disabled) startPoll();
    });
})();
