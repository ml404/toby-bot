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
    const balanceEl = document.getElementById('poker-balance');
    const joinCard = document.getElementById('poker-join-card');

    const btnCheckCall = document.getElementById('poker-action-checkcall');
    const btnRaise = document.getElementById('poker-action-raise');
    const btnFold = document.getElementById('poker-action-fold');
    const btnStart = document.getElementById('poker-action-start');
    const btnCashout = document.getElementById('poker-action-cashout');

    function renderCard(c, faceDown) {
        const span = document.createElement('span');
        span.className = 'poker-card-face';
        if (faceDown) {
            span.classList.add('poker-card-back');
            span.textContent = '?';
            return span;
        }
        // Card strings look like "A♠", "T♥", "9♦", "K♣"
        const suit = c.charAt(c.length - 1);
        if (suit === '♥' || suit === '♦') span.classList.add('poker-card-red');
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
            node.className = 'poker-seat';
            if (idx === meIdx) node.classList.add('poker-seat-me');
            if (idx === state.actorIndex && state.phase !== 'WAITING') node.classList.add('poker-seat-active');
            if (s.status === 'FOLDED') node.classList.add('poker-seat-folded');

            const name = document.createElement('div');
            name.className = 'poker-seat-name';
            name.textContent = (idx === meIdx ? 'You' : 'Player ' + s.discordId) +
                (idx === state.dealerIndex ? ' (D)' : '');
            node.appendChild(name);

            const meta = document.createElement('div');
            meta.className = 'poker-seat-meta';
            meta.textContent = s.chips + ' chips · ' + s.status +
                (s.committedThisRound > 0 ? ' · in: ' + s.committedThisRound : '');
            node.appendChild(meta);

            if (state.phase !== 'WAITING') {
                const cards = document.createElement('div');
                cards.className = 'poker-seat-cards';
                if (idx === meIdx && s.holeCards && s.holeCards.length > 0) {
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

        const isHost = String(state.hostDiscordId) === String(myDiscordId);
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

    function renderResult(result) {
        if (!result) {
            resultEl.textContent = '';
            if (potsEl) { potsEl.innerHTML = ''; potsEl.hidden = true; }
            return;
        }
        const winners = (result.winners || []).map(function (id) { return String(id); }).join(', ');
        const reveals = result.revealedHoleCards || {};
        const lines = [];
        Object.keys(reveals).forEach(function (id) {
            lines.push(id + ': ' + (reveals[id] || []).join(' '));
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
                phaseEl.textContent = state.phase;
                handNumberEl.textContent = state.handNumber;
                potEl.textContent = state.pot;
                currentBetEl.textContent = state.currentBet;
                statusEl.textContent = state.isMyTurn
                    ? 'Your turn.'
                    : (state.phase === 'WAITING'
                        ? 'Waiting for the host to deal.'
                        : 'Waiting for player ' + (state.seats[state.actorIndex] || {}).discordId + '.');
                renderBoard(state.community);
                renderSeats(state);
                renderActions(state);
                renderShotClock(state);
                renderResult(state.lastResult);
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
                if (balanceEl && resp.newBalance != null) balanceEl.textContent = resp.newBalance;
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
                    if (balanceEl && resp.newBalance != null) balanceEl.textContent = resp.newBalance;
                    refresh();
                })
                .catch(function () { showToast('error', 'Network error.'); });
        });
    }

    refresh();
    setInterval(refresh, 2000);
})();
