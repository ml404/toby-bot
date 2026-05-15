(function () {
    "use strict";

    // ----- DOM-agnostic helpers (defined first so jest can require the
    // module under jsdom and exercise them via `window.TobyBlackjackSolo`
    // even though the page-init code below early-returns when the
    // blackjack-solo template isn't present). -----

    // Shared with blackjack-table / casinoholdem / baccarat so every card
    // game on the felt beats out at the same pace.
    var DEALER_REVEAL_STAGGER_MS = (window.CasinoRender && window.CasinoRender.DEALER_REVEAL_STAGGER_MS) || 400;

    function renderCards(container, cards, opts) {
        // Delegate to the shared casino renderer so the deal animation only
        // fires on freshly arrived cards (not every poll re-render). Falls
        // back to the legacy text glyphs if the shared module didn't load.
        if (window.CasinoRender) {
            return window.CasinoRender.renderCards(container, cards, opts);
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
        return { freshCount: 0, settleMs: 0 };
    }

    // Toast helper — funnels every error through the shared window.toast
    // so a future tweak to error styling/duration is one edit. There's no
    // anti-spam toast: rapid resubmits are stopped by keeping the button
    // disabled until the dealer's reveal animation lands, not by piling
    // up notifications.
    function errorToast(msg) {
        if (typeof window !== "undefined" && typeof window.toast === "function") {
            window.toast(msg, "error");
        }
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

    // Shared validation — exposed for jest so the "Enter a positive
    // stake." behaviour can be exercised without booting the page.
    // Returns null when the stake is good, or the user-facing toast
    // message when it isn't. Mirrors casino-game.js's stake rule so
    // every casino page validates the same way.
    function validateStake(rawStake) {
        var stake = parseInt(rawStake, 10);
        if (!stake || stake <= 0) return "Enter a positive stake.";
        return null;
    }

    // Pure detector for the "active hand is a pre-split pair of aces" case.
    // Cards on the wire are formatted by Card.toString() as
    // `<rank-symbol><suit-symbol>` and the ACE rank symbol is the literal
    // "A" — see database/card/Card.kt — so a leading "A" uniquely picks out
    // an ace regardless of suit. Exposed for jest so the hint logic is
    // testable without booting the full poll-driven page.
    function isPairOfAces(cards) {
        if (!cards || cards.length !== 2) return false;
        return cards[0].charAt(0) === "A" && cards[1].charAt(0) === "A";
    }

    // Pure detector for "this resolved round had at least one split-ace
    // hand". Used after settle to append the explainer line so a player
    // who tapped Split before reading the pre-hint still sees why the
    // dealer played immediately.
    function hasSplitAceResolution(perHandResults) {
        if (!perHandResults || !perHandResults.length) return false;
        for (var i = 0; i < perHandResults.length; i++) {
            var entry = perHandResults[i];
            if (entry && entry.fromSplit && entry.cards && entry.cards.length &&
                entry.cards[0].charAt(0) === "A") {
                return true;
            }
        }
        return false;
    }

    // Per-hand result label shared between the single-hand banner and the
    // per-hand split lines. Returns { label, cls } so callers can pick the
    // banner colour from the dominant outcome.
    function labelForResult(result) {
        switch (result) {
            case "PLAYER_BLACKJACK": return { label: "Blackjack! Paid 3:2.", cls: "bj-win" };
            case "PLAYER_WIN":       return { label: "You win.",             cls: "bj-win" };
            case "PUSH":             return { label: "Push — stake refunded.", cls: "muted" };
            case "DEALER_WIN":       return { label: "Dealer wins.",         cls: "bj-lose" };
            case "PLAYER_BUST":      return { label: "Bust.",                cls: "bj-lose" };
            default:                 return { label: "",                     cls: "muted" };
        }
    }

    // Pick a single CSS class for the whole result block when split hands
    // had mixed outcomes: any win > push > any loss. Mirrors the way the
    // wallet actually moves — a player who pushed one and won the other
    // ended the round up, so the banner shouldn't read as a loss.
    function dominantResultClass(perHandResults) {
        var sawWin = false, sawLoss = false;
        for (var i = 0; i < perHandResults.length; i++) {
            var r = perHandResults[i].result;
            if (r === "PLAYER_WIN" || r === "PLAYER_BLACKJACK") sawWin = true;
            else if (r === "DEALER_WIN" || r === "PLAYER_BUST") sawLoss = true;
        }
        if (sawWin) return "bj-win";
        if (sawLoss) return "bj-lose";
        return "muted";
    }

    // Tiny factory the page uses to defer the win/lose banner until the
    // dealer's last freshly-revealed card finishes its CSS animation.
    // Hoisted so jest can drive it directly with fake timers — the
    // bug it fixes (banner painted before the last card slides in)
    // would otherwise need the full DOM-driven state-poll integration
    // to reproduce.
    function createDeferredScheduler() {
        var timer = null;
        return {
            schedule: function (delayMs, fn) {
                if (timer) { clearTimeout(timer); timer = null; }
                if (!delayMs || delayMs <= 0) { fn(); return; }
                timer = setTimeout(function () { timer = null; fn(); }, delayMs);
            },
            cancel: function () {
                if (timer) { clearTimeout(timer); timer = null; }
            },
            isPending: function () { return timer !== null; },
        };
    }

    if (typeof window !== "undefined") {
        window.TobyBlackjackSolo = window.TobyBlackjackSolo || {};
        window.TobyBlackjackSolo.renderSplitHands = renderSplitHands;
        window.TobyBlackjackSolo.createFlashDedup = createFlashDedup;
        window.TobyBlackjackSolo.validateStake = validateStake;
        window.TobyBlackjackSolo.errorToast = errorToast;
        window.TobyBlackjackSolo.createDeferredScheduler = createDeferredScheduler;
        window.TobyBlackjackSolo.isPairOfAces = isPairOfAces;
        window.TobyBlackjackSolo.hasSplitAceResolution = hasSplitAceResolution;
        window.TobyBlackjackSolo.labelForResult = labelForResult;
        window.TobyBlackjackSolo.dominantResultClass = dominantResultClass;
    }

    // ----- Page init. Bail out cleanly if we're loaded without the
    // blackjack-solo template (jest tests, server error pages, etc.).

    var main = document.getElementById("main");
    if (!main) return;
    var guildId = main.dataset.guildId;

    var dealForm = document.getElementById("bj-deal");
    var dealButton = document.getElementById("bj-deal-button");
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
    var splitAcesHintEl = document.getElementById("bj-split-aces-hint");

    var pollTimer = null;
    var resultDefer = createDeferredScheduler();
    var dealBusy = false;
    var actionBusy = false;
    var shouldFlash = createFlashDedup();
    // Cached fragment of the most recent resolution response so the
    // state-driven renderResult below can render the shared "🎰 JACKPOT!"
    // win prefix / "+N to jackpot" loss suffix without the state poll
    // having to carry the per-hand jackpot fields. Keyed by handNumber so
    // a stale body from a previous hand doesn't bleed onto the next one.
    var lastResolutionBody = null;
    function captureResolutionBody(b) {
        lastResolutionBody = {
            handNumber: b.handNumber,
            jackpotPayout: b.jackpotPayout || 0,
            lossTribute: b.lossTribute || 0,
        };
    }

    function renderState(state) {
        if (!state) return;
        tableEl.hidden = false;
        // Lock the Deal button while a hand is in flight. The server enforces
        // this too (SoloDealOutcome.HandInProgress), but disabling the
        // control client-side avoids the "I clicked Deal twice and the
        // wallet went weird" footgun the user reported.
        if (dealButton) {
            var inFlight = state.phase === "PLAYER_TURNS" || state.phase === "DEALER_TURN";
            dealButton.disabled = inFlight;
        }
        // Slow the dealer's reveal/play-out so the hole card flips first
        // and each subsequent draw arrives with a clear beat between
        // cards, instead of everything popping in on the same frame.
        // Capture settleMs so we can hold the win/lose banner until the
        // last fresh card finishes animating (otherwise the result text
        // spoils the reveal in the same paint frame).
        var dealerDeal = renderCards(dealerCardsEl, state.dealer, { staggerMs: DEALER_REVEAL_STAGGER_MS }) || { settleMs: 0 };
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

        // Pre-action hint: when the active hand is a pair of aces and SPLIT
        // is offered, surface the rule that split aces auto-stand on one
        // card. The rules section already documents this (templates/
        // blackjack-solo.html), but it's easy to miss in a long list — and
        // the round visibly "skipping ahead" after the split feels like a
        // bug at the moment it happens.
        var activeCards = (slots && slots.length > 1)
            ? ((slots[seat.activeHandIndex] || slots[0]).cards || [])
            : (seat ? seat.hand : []);
        var showAcesHint = state.phase === "PLAYER_TURNS" && state.isMyTurn &&
            state.canSplit && isPairOfAces(activeCards);

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
            if (splitAcesHintEl) splitAcesHintEl.hidden = !showAcesHint;
        } else {
            hitBtn.disabled = true;
            standBtn.disabled = true;
            doubleBtn.disabled = true;
            splitBtn.disabled = true;
            splitBtn.hidden = true;
            if (splitAcesHintEl) splitAcesHintEl.hidden = true;
            // Defer the .is-active drop to the next frame so it doesn't tear
            // down the seat's compositing layer in the same paint as the
            // chip-stack flourish below — mobile WebKit drops the chip
            // animations otherwise. The `casino-pulse` halo lingers for one
            // extra frame, which is invisible to the eye.
            becomingInactive = !!playerRowEl &&
                playerRowEl.classList.contains("is-active");
        }

        if (state.lastResult && state.phase === "RESOLVED") {
            // Hold the banner / chip flourish / win sound until the
            // last freshly-revealed dealer card has finished its CSS
            // animation. dealerDeal.settleMs is 0 when nothing fresh
            // landed this poll (e.g. a re-render after an in-flight
            // settle), so the banner draws immediately in that case.
            resultDefer.schedule(dealerDeal.settleMs, function () { renderResult(state, seat); });
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
        // Fire the celebratory chip stack + win/lose cue once per hand
        // via the shared casino-win-settle helper. Driven off the seat's
        // total payout, which already aggregates every split branch —
        // so a "push hand 1, win hand 2" round still triggers the win
        // flourish even though seatResults only carries hand 0's outcome.
        if (shouldFlash(state) && window.TobyCasinoWinSettle) {
            var payout = (r.payouts || {})[seat.discordId];
            window.TobyCasinoWinSettle.fire({
                win: !!(payout && payout > 0),
                net: payout || 0,
            }, playerRowEl);
        }

        // Reset the result block in case it's a re-render after a previous
        // round (textContent assign replaces children, including any
        // explainer/per-hand lines we appended last time).
        resultEl.textContent = "";

        // Match the cached resolution body to the current hand so a stale
        // jackpot prefix / loss-tribute suffix from a previous hand never
        // leaks onto the next. Falls back to no body (plain text) when
        // the cache hasn't caught up yet — e.g. a state poll arrives
        // before the deal/action POST resolves.
        var jackpot = window.TobyJackpot;
        var body = (jackpot && lastResolutionBody &&
            lastResolutionBody.handNumber === r.handNumber) ? lastResolutionBody : null;
        var jackpotPct = (body && typeof body.jackpotTierPayoutPct === "number")
            ? body.jackpotTierPayoutPct * 100 : 0;
        var jackpotPrefixHtml = (body && jackpot)
            ? jackpot.jackpotPrefixHtml(body.jackpotPayout, jackpotPct) : "";
        var lossTributeSuffixHtml = (body && jackpot)
            ? jackpot.lossTributeSuffix(body) : "";
        // Spin the visual wheel once per resolution if the server picked
        // a tier. No-op when the response isn't a jackpot hit.
        if (body && jackpot && typeof jackpot.spinWheelFor === "function") {
            jackpot.spinWheelFor(body);
        }

        // For a split round, seatResults only carries hand 0's outcome —
        // see BlackjackService.settleSolo (firstHandResult). Surface every
        // hand's result so a "push + win" round doesn't read as a pure
        // push and silently drop the second hand's payout from view.
        var seatPerHand = (r.perHandResults || []).filter(function (p) {
            return p.discordId === seat.discordId;
        });
        if (seatPerHand.length > 1) {
            resultEl.className = "bj-result " + dominantResultClass(seatPerHand);
            // Jackpot prefix at the top of the split block; the seat-level
            // body.jackpotPayout already aggregates every winning branch.
            if (jackpotPrefixHtml) {
                var prefixEl = document.createElement("div");
                prefixEl.innerHTML = jackpotPrefixHtml;
                resultEl.appendChild(prefixEl);
                resultEl.classList.add("bj-result-jackpot");
            }
            seatPerHand.forEach(function (entry, idx) {
                var line = document.createElement("div");
                var info = labelForResult(entry.result);
                var prefix = "Hand " + (idx + 1) + ": ";
                var suffix = entry.payout > 0 ? " (+" + entry.payout + ")" : "";
                line.textContent = prefix + info.label + suffix;
                resultEl.appendChild(line);
            });
            // Loss-tribute suffix at the bottom — body.lossTribute aggregates
            // every losing branch.
            if (lossTributeSuffixHtml) {
                var tributeEl = document.createElement("div");
                tributeEl.innerHTML = lossTributeSuffixHtml;
                resultEl.appendChild(tributeEl);
            }
        } else {
            var info = labelForResult(seatResult);
            resultEl.className = "bj-result " + info.cls;
            if (info.cls === "bj-win" && body) {
                resultEl.innerHTML = jackpot.renderWinHtml(
                    resultEl, body, "bj-result-jackpot", info.label
                );
            } else if (info.cls === "bj-lose" && body) {
                resultEl.innerHTML = info.label + lossTributeSuffixHtml;
            } else {
                resultEl.textContent = info.label;
            }
        }

        // Post-resolution explainer: if any branch was a split-ace, append
        // a small italic note explaining why the dealer played immediately.
        // Helps a player who tapped Split before reading the pre-hint.
        if (hasSplitAceResolution(r.perHandResults)) {
            var note = document.createElement("small");
            note.className = "muted";
            note.style.display = "block";
            note.style.fontStyle = "italic";
            note.textContent = "Split aces auto-stood on one card by house rule.";
            resultEl.appendChild(note);
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

    dealForm.addEventListener("submit", function (e) {
        e.preventDefault();
        // Silently no-op when a hand is already being dealt — the Deal
        // button is disabled until the reveal lands, but a held-down
        // click can still fire submit events on the form. No toast here:
        // the disabled button is the feedback.
        if (dealBusy) return;
        var stakeError = validateStake(stakeInput.value);
        if (stakeError) {
            errorToast(stakeError);
            return;
        }
        var stake = parseInt(stakeInput.value, 10);
        // Cancel any deferred banner from the previous hand so a fast
        // re-deal doesn't paint stale "You win." text over the new felt.
        resultDefer.cancel();
        // Drop the cached jackpot/tribute body too — keying solely on
        // handNumber leaves a window where the new hand happens to reuse
        // the same number (e.g. solo-table re-creation resets it to 1).
        lastResolutionBody = null;
        dealBusy = true;
        if (dealButton) dealButton.disabled = true;
        // POSTs go via TobyApi.postJson so the Spring Security CSRF
        // header (read off the <meta name="_csrf"> tag in the head
        // fragment) is included — without it Spring rejects the request
        // with 403.
        window.TobyApi.postJson("/blackjack/" + guildId + "/solo/deal", { stake: stake })
            .then(function (b) {
                if (!b.ok) {
                    errorToast(b.error || "Deal failed.");
                    return;
                }
                // Always reflect the post-deal escrow in the wallet display, not
                // just on the natural-BJ resolved short-circuit. Without this the
                // wallet looked like it stayed full all hand and only "lost" the
                // stake on resolution — making each subsequent action feel like
                // it was charging stake again.
                window.TobyBalance.update(balanceEl, b.newBalance);
                if (b.resolved) {
                    captureResolutionBody(b);
                    refreshState();
                } else {
                    refreshState();
                    startPoll();
                }
            })
            .catch(function () { errorToast("Network error."); })
            .then(function () { dealBusy = false; });
    });

    function postAction(action) {
        // Drop a click that lands while a previous request is still in
        // flight. We don't toggle the action buttons' `disabled` attribute
        // here — renderState already drives that off the polled phase
        // (`state.isMyTurn` flips them after each round-trip), so a
        // local override would just duplicate that work and trip up
        // tests that exercise multiple clicks against a stubbed poll.
        if (actionBusy) return Promise.resolve();
        actionBusy = true;
        return window.TobyApi.postJson("/blackjack/" + guildId + "/solo/action", { action: action })
            .then(function (b) {
                if (!b.ok) {
                    errorToast(b.error || "Action failed.");
                    return;
                }
                // Refresh on every successful action — the server echoes the
                // live wallet on Continued too. HIT/STAND don't move the
                // balance, but DOUBLE/SPLIT pre-debit additional stake mid-hand
                // and the player should see that immediately.
                window.TobyBalance.update(balanceEl, b.newBalance);
                if (b.resolved) captureResolutionBody(b);
                refreshState();
            })
            .catch(function () { errorToast("Network error."); })
            .then(function () { actionBusy = false; });
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
