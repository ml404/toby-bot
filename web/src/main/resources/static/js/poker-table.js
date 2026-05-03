// /poker table — polls /poker/{guildId}/{tableId}/state every 2s and
// renders the table state, then drives Check/Call/Raise/Fold/Cashout
// via /poker/{guildId}/{tableId}/{action}. Hole cards for other
// players are masked server-side, so a hostile client can't read the
// JSON to see opponents' cards — we just render whatever we receive.
(function () {
    'use strict';

    const main = document.getElementById('main');
    if (!main) return;
    const guildId = main.dataset.guildId;
    const tableId = main.dataset.tableId;
    const myDiscordId = main.dataset.myDiscordId;
    if (!guildId || !tableId) return;

    const phaseEl = document.getElementById('poker-phase');
    const handNumberEl = document.getElementById('poker-hand-number');
    const potEl = document.getElementById('poker-pot');
    const currentBetEl = document.getElementById('poker-current-bet');
    const boardEl = document.getElementById('poker-board');
    const seatsEl = document.getElementById('poker-seats');
    const statusEl = document.getElementById('poker-status');
    const resultEl = document.getElementById('poker-result');
    const potsEl = document.getElementById('poker-pots');
    const shotClockEl = document.getElementById('poker-shot-clock');
    let shotClockTicker = null;
    // Tracks the handNumber of the last result we played the chip-stack
    // flourish on, so the 2s polling doesn't re-trigger the animation.
    let lastFlashedHand = null;
    const balanceEl = document.getElementById('poker-balance');
    const joinCard = document.getElementById('poker-join-card');

    const btnCheckCall = document.getElementById('poker-action-checkcall');
    const btnRaise = document.getElementById('poker-action-raise');
    const btnFold = document.getElementById('poker-action-fold');
    const btnStart = document.getElementById('poker-action-start');
    const btnCashout = document.getElementById('poker-action-cashout');
    const actorPillEl = document.getElementById('poker-actor-pill');

    // A single rendered card — uses the shared casino-card-glyph styles so
    // poker and blackjack render cards identically.
    function renderCard(c, faceDown) {
        const span = document.createElement('span');
        span.className = 'casino-card-glyph';
        if (faceDown) {
            span.classList.add('is-hidden');
            span.textContent = '🂠';
            return span;
        }
        // Card strings look like "A♠", "10♥", "9♦", "K♣"
        const suit = c.charAt(c.length - 1);
        if (suit === '♥' || suit === '♦') span.classList.add('is-red');
        span.textContent = c;
        return span;
    }

    function renderBoard(community) {
        boardEl.innerHTML = '';
        if (!community || community.length === 0) {
            const ph = document.createElement('span');
            ph.className = 'muted';
            ph.textContent = '— no community cards yet —';
            boardEl.appendChild(ph);
            return;
        }
        community.forEach(function (c) { boardEl.appendChild(renderCard(c, false)); });
    }

    function renderSeats(state) {
        seatsEl.innerHTML = '';
        const meIdx = state.mySeatIndex;
        state.seats.forEach(function (s, idx) {
            const node = document.createElement('div');
            node.className = 'poker-seat casino-seat';
            // s.discordId is already a string from the server projection
            // (Long → String in PokerWebService.SeatView); no JS rounding.
            node.dataset.discordId = s.discordId;
            const isMe = idx === meIdx;
            if (isMe) node.classList.add('poker-seat-me', 'is-me');
            if (idx === state.actorIndex && state.phase !== 'WAITING') node.classList.add('poker-seat-active', 'is-active');
            if (s.status === 'FOLDED') node.classList.add('poker-seat-folded', 'is-folded');

            // Avatar + display-name header — built by the shared CasinoRender so
            // blackjack and poker get the same look. Dealer button suffix is
            // tucked into the meta column on the right.
            const dealerSuffix = idx === state.dealerIndex ? ' (D)' : '';
            const meta = s.chips + ' chips · ' + s.status +
                (s.committedThisRound > 0 ? ' · in: ' + s.committedThisRound : '') +
                dealerSuffix;
            if (window.CasinoRender) {
                node.appendChild(window.CasinoRender.makeSeatHeader(s, { isMe: isMe, metaText: meta }));
            } else {
                const name = document.createElement('div');
                name.className = 'poker-seat-name';
                name.textContent = (isMe ? 'You' : (s.displayName || ('Player ' + s.discordId))) + dealerSuffix;
                node.appendChild(name);
                const metaEl = document.createElement('div');
                metaEl.className = 'poker-seat-meta';
                metaEl.textContent = meta;
                node.appendChild(metaEl);
            }

            if (state.phase !== 'WAITING') {
                const cards = document.createElement('div');
                cards.className = 'poker-seat-cards casino-cards';
                if (isMe && s.holeCards && s.holeCards.length > 0) {
                    s.holeCards.forEach(function (c) { cards.appendChild(renderCard(c, false)); });
                } else if (s.status !== 'FOLDED' && s.status !== 'SITTING_OUT') {
                    // Server returns empty list for masked seats; render two backs.
                    cards.appendChild(renderCard('', true));
                    cards.appendChild(renderCard('', true));
                }
                node.appendChild(cards);
            }
            seatsEl.appendChild(node);
        });
    }

    function renderActorPill(state) {
        if (!actorPillEl || !window.CasinoRender) return;
        const acting = state.phase !== 'WAITING' ? state.seats[state.actorIndex] : null;
        const isMe = acting && state.mySeatIndex !== null && state.actorIndex === state.mySeatIndex;
        window.CasinoRender.renderActorPill(actorPillEl, acting, {
            label: isMe ? 'Your turn' : 'Acting',
        });
    }

    function renderActions(state) {
        const seated = state.mySeatIndex !== null && state.mySeatIndex !== undefined;
        if (joinCard) joinCard.hidden = seated;

        btnCheckCall.disabled = !state.isMyTurn;
        btnCheckCall.textContent = state.canCall
            ? 'Call ' + state.callAmount
            : 'Check';
        btnRaise.disabled = !state.canRaise;
        btnRaise.textContent = 'Raise (+' + (state.raiseAmount - state.callAmount) + ')';
        btnFold.disabled = !state.isMyTurn;

        // hostDiscordId comes through as a string (see PokerWebService);
        // myDiscordId is read from the data-my-discord-id attribute, also a string.
        const isHost = state.hostDiscordId === myDiscordId;
        const canStart = isHost && state.phase === 'WAITING' && state.seats.length >= 2;
        btnStart.hidden = !isHost;
        btnStart.disabled = !canStart;

        btnCashout.hidden = !seated;
        btnCashout.disabled = state.phase !== 'WAITING';
    }

    /**
     * Render the per-actor shot-clock countdown. The server sends a
     * deadline epoch-millis instead of a "seconds remaining" so the
     * client can drive a smooth 1Hz tick without depending on the 2s
     * polling cadence — otherwise the visible countdown would jump in
     * 2-second steps and feel laggy. When no clock is armed, the
     * element is hidden and any running ticker is stopped.
     */
    function renderShotClock(state) {
        if (!shotClockEl) return;
        if (shotClockTicker) { clearInterval(shotClockTicker); shotClockTicker = null; }
        const deadline = state.currentActorDeadlineEpochMillis;
        if (!state.shotClockSeconds || !deadline || state.phase === 'WAITING') {
            shotClockEl.hidden = true;
            shotClockEl.textContent = '';
            return;
        }
        function tick() {
            const remainingMs = deadline - Date.now();
            if (remainingMs <= 0) {
                shotClockEl.textContent = 'Time!';
                if (shotClockTicker) { clearInterval(shotClockTicker); shotClockTicker = null; }
                return;
            }
            shotClockEl.textContent = Math.ceil(remainingMs / 1000) + 's';
        }
        shotClockEl.hidden = false;
        tick();
        shotClockTicker = setInterval(tick, 1000);
    }

    function renderResult(result, state) {
        if (!result) {
            resultEl.textContent = '';
            if (potsEl) { potsEl.innerHTML = ''; potsEl.hidden = true; }
            return;
        }
        // Map id → displayName from the live snapshot so winner / showdown
        // labels show real Discord names instead of raw ids.
        const nameById = {};
        ((state && state.seats) || []).forEach(function (s) {
            nameById[s.discordId] = s.displayName || ('Player ' + s.discordId);
        });
        const labelFor = function (id) { return nameById[id] || String(id); };
        const winners = (result.winners || []).map(labelFor).join(', ');
        const reveals = result.revealedHoleCards || {};
        const lines = [];
        Object.keys(reveals).forEach(function (id) {
            lines.push(labelFor(id) + ': ' + (reveals[id] || []).join(' '));
        });
        resultEl.textContent = 'Hand #' + result.handNumber +
            ' resolved. Winners: ' + winners +
            ' · pot ' + result.pot + ' (rake ' + result.rake + ' → jackpot)' +
            (lines.length ? ' · showdown: ' + lines.join(' | ') : '');
        if (potsEl && window.TobyPokerPots) {
            window.TobyPokerPots.render({
                containerEl: potsEl,
                pots: result.pots || [],
                refundedByDiscordId: result.refundedByDiscordId || {},
            });
        }

        // Fire the celebratory chip stack once per hand on each seat that
        // received a positive payout. The 2s polling would otherwise
        // re-trigger every cycle while the result is on screen, so we
        // key off handNumber.
        if (result.handNumber !== lastFlashedHand && window.CasinoRender) {
            lastFlashedHand = result.handNumber;
            const payouts = result.payoutByDiscordId || {};
            let myPaid = false;
            Object.keys(payouts).forEach(function (id) {
                const amount = payouts[id];
                if (!amount || amount <= 0) return;
                const seatEl = seatsEl.querySelector('[data-discord-id="' + id + '"]');
                if (seatEl) window.CasinoRender.flashChipsOn(seatEl, amount);
                if (id === myDiscordId) myPaid = true;
            });
            // Sound cue keyed off the viewer's outcome — a win is a win
            // for the player even if a side pot went elsewhere.
            if (window.CasinoSounds) {
                window.CasinoSounds.play(myPaid ? "win" : "lose");
            }
        }
    }

    function refresh() {
        fetch('/poker/' + guildId + '/' + tableId + '/state', { credentials: 'same-origin' })
            .then(function (r) {
                if (r.status === 404) {
                    statusEl.textContent = 'Table closed.';
                    return null;
                }
                if (!r.ok) return null;
                return r.json();
            })
            .then(function (state) {
                if (!state) return;
                const freeBadge = document.getElementById('poker-free-badge');
                if (freeBadge) freeBadge.hidden = !state.isFreePlay;
                phaseEl.textContent = state.phase;
                handNumberEl.textContent = state.handNumber;
                potEl.textContent = state.pot;
                currentBetEl.textContent = state.currentBet;
                const actorSeat = state.seats[state.actorIndex] || {};
                const actorName = actorSeat.displayName || ('player ' + actorSeat.discordId);
                statusEl.textContent = state.isMyTurn
                    ? 'Your turn.'
                    : (state.phase === 'WAITING'
                        ? 'Waiting for the host to deal.'
                        : 'Waiting for ' + actorName + '.');
                renderBoard(state.community);
                renderSeats(state);
                renderActorPill(state);
                renderActions(state);
                renderShotClock(state);
                renderResult(state.lastResult, state);
            })
            .catch(function () { /* keep last known state */ });
    }

    function postAction(action) {
        return window.TobyApi.postJson(
            '/poker/' + guildId + '/' + tableId + '/action',
            { action: action }
        ).then(function (resp) {
            if (!resp || !resp.ok) {
                showToast('error', (resp && resp.error) || 'Action failed.');
            }
            refresh();
        }).catch(function () { showToast('error', 'Network error.'); });
    }

    btnCheckCall.addEventListener('click', function () { postAction('checkcall'); });
    btnRaise.addEventListener('click', function () { postAction('raise'); });
    btnFold.addEventListener('click', function () { postAction('fold'); });

    btnStart.addEventListener('click', function () {
        window.TobyApi.postJson('/poker/' + guildId + '/' + tableId + '/start', {})
            .then(function (resp) {
                if (!resp || !resp.ok) {
                    showToast('error', (resp && resp.error) || 'Could not deal.');
                } else {
                    showToast('success', 'Hand #' + resp.handNumber + ' dealt.');
                }
                refresh();
            })
            .catch(function () { showToast('error', 'Network error.'); });
    });

    btnCashout.addEventListener('click', function () {
        window.TobyApi.postJson('/poker/' + guildId + '/' + tableId + '/cashout', {})
            .then(function (resp) {
                if (!resp || !resp.ok) {
                    showToast('error', (resp && resp.error) || 'Could not cash out.');
                    refresh();
                    return;
                }
                showToast('success', 'Cashed out ' + resp.chipsReturned + ' chips.');
                window.TobyBalance.update(balanceEl, resp.newBalance);
                window.location.href = '/poker/' + guildId;
            })
            .catch(function () { showToast('error', 'Network error.'); });
    });

    const joinForm = document.getElementById('poker-join');
    if (joinForm) {
        joinForm.addEventListener('submit', function (e) {
            e.preventDefault();
            const buyIn = parseInt(document.getElementById('poker-join-buyin').value, 10);
            if (!buyIn) {
                showToast('error', 'Buy-in is required.');
                return;
            }
            window.TobyApi.postJson('/poker/' + guildId + '/' + tableId + '/join', { buyIn: buyIn })
                .then(function (resp) {
                    if (!resp || !resp.ok) {
                        showToast('error', (resp && resp.error) || 'Could not join.');
                        return;
                    }
                    showToast('success', 'You sat down with ' + buyIn + ' chips.');
                    window.TobyBalance.update(balanceEl, resp.newBalance);
                    refresh();
                })
                .catch(function () { showToast('error', 'Network error.'); });
        });
    }

    refresh();
    setInterval(refresh, 2000);
})();
