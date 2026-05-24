// /pvp — unified PvP page. Tab strip switches between Duel, RPS,
// TicTacToe, Connect 4 (the last three are placeholders until their
// controller endpoints land). Duel: challenge form posts to
// /pvp/{guildId}/duel/challenge; the inbox panel polls
// /pvp/{guildId}/duel/pending every 5 seconds and posts to /accept or
// /decline via the shared CSRF-aware fetch wrapper.
//
// UMD-style export: `playDuelResolution`, `makeFigure`, and `formatExpiry`
// are pulled out for unit testing (see casino-jackpot-wheel.js for the
// same pattern). Page-init code only runs when a real `document` with
// the pvp page DOM is present.
(function (root) {
    'use strict';

    function makeFigure(name, avatarUrl, side, doc) {
        const d = doc || (root && root.document);
        const fig = d.createElement('div');
        fig.className = 'duel-figure duel-figure--' + side;
        const av = d.createElement('div');
        av.className = 'duel-figure-avatar';
        if (avatarUrl) {
            const img = d.createElement('img');
            img.src = avatarUrl;
            img.alt = '';
            img.loading = 'lazy';
            av.appendChild(img);
        } else {
            // Mirror the CSS-rendered initial fallback the casino uses when
            // a member has no avatar URL.
            av.classList.add('is-fallback');
            av.dataset.initial = (name || '?').trim().charAt(0).toUpperCase() || '?';
        }
        fig.appendChild(av);
        const cap = d.createElement('div');
        cap.className = 'duel-figure-name';
        cap.textContent = name || 'Unknown';
        fig.appendChild(cap);
        return fig;
    }

    function formatExpiry(createdAtSeconds, ttlSeconds, nowMs) {
        if (!createdAtSeconds || !ttlSeconds) return '';
        const now = nowMs == null ? Date.now() : nowMs;
        const expiresAtMs = (createdAtSeconds + ttlSeconds) * 1000;
        const remaining = Math.max(0, Math.round((expiresAtMs - now) / 1000));
        if (remaining <= 0) return 'expiring…';
        if (remaining < 60) return 'expires in ' + remaining + 's';
        const minutes = Math.floor(remaining / 60);
        const seconds = remaining % 60;
        return seconds > 0
            ? 'expires in ' + minutes + 'm ' + seconds + 's'
            : 'expires in ' + minutes + 'm';
    }

    function playDuelResolution(row, resp, opts) {
        opts = opts || {};
        const doc = opts.doc || (root && root.document);
        const win = opts.window || root;
        const onDismiss = typeof opts.onDismiss === 'function' ? opts.onDismiss : function () {};
        const autoDismissMs = opts.autoDismissMs == null ? 6000 : opts.autoDismissMs;
        const fadeOutMs = opts.fadeOutMs == null ? 200 : opts.fadeOutMs;

        const initiatorName = row.dataset.initiatorName || 'Challenger';
        const initiatorAvatar = row.dataset.initiatorAvatar || null;
        const opponentName = row.dataset.opponentName || 'You';
        const opponentAvatar = row.dataset.opponentAvatar || null;
        const winnerId = resp.winnerDiscordId;
        const initiatorWon = winnerId === row.dataset.initiatorDiscordId;
        const winnerName = initiatorWon ? initiatorName : opponentName;

        const reduceMotion = !!(win && win.matchMedia &&
            win.matchMedia('(prefers-reduced-motion: reduce)').matches);

        const overlay = doc.createElement('div');
        overlay.className = 'duel-resolution-overlay';
        if (reduceMotion) overlay.classList.add('is-reduced');
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-label', 'Duel result');

        const arena = doc.createElement('div');
        arena.className = 'duel-arena';

        const left = makeFigure(initiatorName, initiatorAvatar, 'left', doc);
        const right = makeFigure(opponentName, opponentAvatar, 'right', doc);
        if (initiatorWon) left.classList.add('is-winner'); else left.classList.add('is-loser');
        if (initiatorWon) right.classList.add('is-loser'); else right.classList.add('is-winner');

        const flash = doc.createElement('div');
        flash.className = 'duel-flash';
        // Position the bang near the winner's muzzle, not centred between
        // the two figures — CSS reads `from-left` / `from-right` and
        // sets a translateX offset toward that side.
        flash.classList.add(initiatorWon ? 'from-left' : 'from-right');
        flash.textContent = '💥';

        arena.appendChild(left);
        arena.appendChild(flash);
        arena.appendChild(right);
        overlay.appendChild(arena);

        const pill = doc.createElement('div');
        pill.className = 'duel-credits-pill';
        pill.textContent = '+' + resp.pot + ' credits';
        if (initiatorWon) pill.classList.add('flies-left'); else pill.classList.add('flies-right');
        overlay.appendChild(pill);

        const resultLine = doc.createElement('div');
        resultLine.className = 'duel-result-line';
        resultLine.textContent = 'Winner: ' + winnerName + ' took ' + resp.pot +
            ' credits (' + resp.lossTribute + ' to jackpot)';
        overlay.appendChild(resultLine);

        const hint = doc.createElement('div');
        hint.className = 'duel-dismiss-hint';
        hint.textContent = 'Click anywhere or press Esc to continue';
        overlay.appendChild(hint);

        let dismissed = false;
        let autoDismiss;
        function dismiss() {
            if (dismissed) return;
            dismissed = true;
            clearTimeout(autoDismiss);
            doc.removeEventListener('keydown', onKey);
            overlay.classList.add('is-dismissing');
            // Brief fade-out so it doesn't pop off.
            setTimeout(function () {
                if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
                onDismiss();
            }, fadeOutMs);
        }
        function onKey(e) { if (e.key === 'Escape') dismiss(); }
        overlay.addEventListener('click', dismiss);
        doc.addEventListener('keydown', onKey);
        autoDismiss = setTimeout(dismiss, autoDismissMs);

        doc.body.appendChild(overlay);
        return { overlay: overlay, dismiss: dismiss };
    }

    // Build the same offscreen-row + fake-resp shape the live accept
    // handler and the preview button use, then hand it to
    // playDuelResolution. Lets the initiator's browser replay the
    // acceptor's animation when their `/outgoing` poll surfaces a
    // freshly-resolved duel from the server-side resolution cache.
    function playFromResolution(res, opts) {
        if (!res || !res.winnerDiscordId) return null;
        opts = opts || {};
        const doc = opts.doc || (root && root.document);
        const row = doc.createElement('div');
        row.dataset.initiatorDiscordId = String(res.initiatorDiscordId);
        row.dataset.initiatorName = res.initiatorName || '';
        if (res.initiatorAvatarUrl) row.dataset.initiatorAvatar = res.initiatorAvatarUrl;
        row.dataset.opponentDiscordId = String(res.opponentDiscordId);
        row.dataset.opponentName = res.opponentName || '';
        if (res.opponentAvatarUrl) row.dataset.opponentAvatar = res.opponentAvatarUrl;

        const winnerId = String(res.winnerDiscordId);
        const loserDiscordId = winnerId === String(res.initiatorDiscordId)
            ? String(res.opponentDiscordId)
            : String(res.initiatorDiscordId);
        const resp = {
            winnerDiscordId: winnerId,
            loserDiscordId: loserDiscordId,
            stake: res.pot ? Math.floor(res.pot / 2) : 0,
            pot: res.pot || 0,
            lossTribute: res.lossTribute || 0,
        };
        return playDuelResolution(row, resp, opts);
    }

    const api = {
        playDuelResolution: playDuelResolution,
        makeFigure: makeFigure,
        formatExpiry: formatExpiry,
        playFromResolution: playFromResolution,
    };
    if (root) root.TobyDuel = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }

    // Page-init: only run in the browser when the duel page DOM is present.
    if (!root || !root.document) return;
    const document = root.document;

    const main = document.getElementById('main');
    if (!main) return;
    const guildId = main.dataset.guildId;
    if (!guildId) return;
    const ttlSeconds = parseInt(main.dataset.ttlSeconds, 10) || 0;

    // Tab strip — Duel + three placeholder tabs (RPS/TTT/C4 land in a
    // follow-up PR). One panel visible at a time; chips drive `hidden`
    // on the panels and `aria-selected` on the tabs.
    const tabs = Array.prototype.slice.call(document.querySelectorAll('.pvp-tab'));
    const panels = Array.prototype.slice.call(document.querySelectorAll('.pvp-panel'));
    function activateTab(slug) {
        tabs.forEach(function (t) {
            const isActive = t.dataset.pvpTab === slug;
            t.classList.toggle('is-active', isActive);
            t.setAttribute('aria-selected', isActive ? 'true' : 'false');
        });
        panels.forEach(function (p) {
            p.hidden = p.id !== ('pvp-panel-' + slug);
        });
    }
    tabs.forEach(function (t) {
        t.addEventListener('click', function () { activateTab(t.dataset.pvpTab); });
    });

    const balanceEl = document.getElementById('duel-balance');
    const challengeForm = document.getElementById('duel-challenge');
    const pendingList = document.getElementById('duel-pending-list');
    const outgoingList = document.getElementById('duel-outgoing-list');
    const previewBtn = document.getElementById('duel-preview');

    function makeMemberCell(name, avatarUrl) {
        const cell = document.createElement('div');
        cell.className = 'member-cell';
        if (avatarUrl) {
            const img = document.createElement('img');
            img.className = 'avatar';
            img.src = avatarUrl;
            img.alt = '';
            img.loading = 'lazy';
            cell.appendChild(img);
        }
        const nameEl = document.createElement('span');
        nameEl.className = 'lb-name';
        // textContent prevents nicknames like "<script>" from breaking out
        // of the cell — pvp.html relies on Thymeleaf escaping for the same
        // reason, so the dynamic path needs the same guarantee.
        nameEl.textContent = name || '';
        cell.appendChild(nameEl);
        return cell;
    }

    function buildRow(row, opts) {
        const node = document.createElement('div');
        node.className = 'duel-pending-row';
        node.dataset.duelId = String(row.duelId);
        node.dataset.stake = String(row.stake);
        if (row.createdAtEpochSeconds != null) {
            node.dataset.createdAt = String(row.createdAtEpochSeconds);
        }
        // Stash both sides so the accept resolution animation can build the
        // arena without re-fetching the offer (the registry has already
        // consumed it by the time the response lands).
        if (row.initiatorName) node.dataset.initiatorName = row.initiatorName;
        if (row.initiatorAvatarUrl) node.dataset.initiatorAvatar = row.initiatorAvatarUrl;
        if (row.opponentName) node.dataset.opponentName = row.opponentName;
        if (row.opponentAvatarUrl) node.dataset.opponentAvatar = row.opponentAvatarUrl;
        node.dataset.initiatorDiscordId = String(row.initiatorDiscordId);
        node.dataset.opponentDiscordId = String(row.opponentDiscordId);

        const info = document.createElement('div');
        info.className = 'duel-pending-info';

        const who = document.createElement('div');
        who.className = 'duel-pending-who';
        const label = document.createElement('span');
        label.className = 'muted';
        label.textContent = opts.directionLabel;
        who.appendChild(label);
        who.appendChild(makeMemberCell(opts.name, opts.avatarUrl));
        info.appendChild(who);

        const meta = document.createElement('div');
        meta.className = 'duel-pending-meta';
        const stake = document.createElement('span');
        stake.appendChild(document.createTextNode('for '));
        const stakeStrong = document.createElement('strong');
        stakeStrong.textContent = String(row.stake);
        stake.appendChild(stakeStrong);
        stake.appendChild(document.createTextNode(' credits'));
        meta.appendChild(stake);

        const expiry = document.createElement('span');
        expiry.className = 'duel-expiry muted';
        expiry.textContent = formatExpiry(row.createdAtEpochSeconds, ttlSeconds);
        meta.appendChild(expiry);
        info.appendChild(meta);

        node.appendChild(info);

        const actions = document.createElement('div');
        actions.className = 'duel-pending-actions';
        opts.buttons.forEach(function (b) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = b.className;
            btn.textContent = b.label;
            actions.appendChild(btn);
        });
        node.appendChild(actions);

        return node;
    }

    function renderPending(rows) {
        if (!pendingList) return;
        pendingList.innerHTML = '';
        if (!rows || rows.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'muted';
            empty.textContent = 'No pending duels right now.';
            pendingList.appendChild(empty);
            return;
        }
        rows.forEach(function (row) {
            pendingList.appendChild(buildRow(row, {
                directionLabel: 'From',
                name: row.initiatorName,
                avatarUrl: row.initiatorAvatarUrl,
                buttons: [
                    { className: 'btn-primary duel-accept', label: 'Accept' },
                    { className: 'btn-secondary duel-decline', label: 'Decline' },
                ],
            }));
        });
    }

    function refreshPending() {
        fetch('/pvp/' + guildId + '/duel/pending', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(renderPending)
            .catch(function () { /* keep last known state */ });
    }

    function renderOutgoing(rows) {
        if (!outgoingList) return;
        outgoingList.innerHTML = '';
        if (!rows || rows.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'muted';
            empty.textContent = 'No outgoing challenges right now.';
            outgoingList.appendChild(empty);
            return;
        }
        rows.forEach(function (row) {
            outgoingList.appendChild(buildRow(row, {
                directionLabel: 'To',
                name: row.opponentName,
                avatarUrl: row.opponentAvatarUrl,
                buttons: [
                    { className: 'btn-secondary duel-cancel', label: 'Cancel' },
                ],
            }));
        });
    }

    function refreshOutgoing() {
        fetch('/pvp/' + guildId + '/duel/outgoing', { credentials: 'same-origin' })
            .then(function (r) {
                return r.ok ? r.json() : { pending: [], resolutions: [] };
            })
            .then(function (payload) {
                renderOutgoing(payload.pending || []);
                (payload.resolutions || []).forEach(playFromResolution);
            })
            .catch(function () { /* keep last known state */ });
    }

    // Tick the expiry labels on already-rendered rows without re-fetching;
    // the 5s poll refresh would also redraw them, but a 1s tick keeps the
    // countdown smooth between fetches.
    function tickExpiries() {
        const rows = document.querySelectorAll('.duel-pending-row[data-created-at]');
        rows.forEach(function (row) {
            const createdAt = parseInt(row.dataset.createdAt, 10);
            if (!createdAt) return;
            const label = row.querySelector('.duel-expiry');
            if (label) label.textContent = formatExpiry(createdAt, ttlSeconds);
        });
    }

    function refreshAll() {
        refreshPending();
        refreshOutgoing();
    }

    if (challengeForm) {
        challengeForm.addEventListener('submit', function (e) {
            e.preventDefault();
            const opponent = document.getElementById('duel-opponent').value;
            const stake = parseInt(document.getElementById('duel-stake').value, 10);
            if (!opponent) {
                toast('error', 'Pick someone from the list.');
                return;
            }
            if (!stake) {
                toast('error', 'Stake is required.');
                return;
            }
            window.TobyApi.postJson('/pvp/' + guildId + '/duel/challenge', {
                opponentDiscordId: opponent,
                stake: stake
            }).then(function (resp) {
                if (!resp || !resp.ok) {
                    toast('error', (resp && resp.error) || 'Challenge failed.');
                    return;
                }
                toast('success', 'Challenge sent. Waiting for them to accept.');
                refreshOutgoing();
            }).catch(function () { toast('error', 'Network error sending challenge.'); });
        });
    }

    if (pendingList) {
        pendingList.addEventListener('click', function (e) {
            const btn = e.target.closest('button');
            if (!btn) return;
            const row = btn.closest('.duel-pending-row');
            if (!row) return;
            const duelId = row.dataset.duelId;
            if (!duelId) return;
            const isAccept = btn.classList.contains('duel-accept');
            const isDecline = btn.classList.contains('duel-decline');
            if (!isAccept && !isDecline) return;

            const path = '/pvp/' + guildId + '/duel/' + duelId + (isAccept ? '/accept' : '/decline');
            window.TobyApi.postJson(path, {}).then(function (resp) {
                if (!resp || !resp.ok) {
                    toast('error', (resp && resp.error) || 'Action failed.');
                    refreshAll();
                    return;
                }
                if (isAccept && resp.winnerDiscordId) {
                    playDuelResolution(row, resp, { onDismiss: refreshAll });
                    // refreshAll() fires on dismiss so the inbox row doesn't
                    // vanish mid-animation.
                    return;
                } else if (isDecline) {
                    toast('success', 'Declined.');
                }
                refreshAll();
            }).catch(function () { toast('error', 'Network error.'); });
        });
    }

    if (outgoingList) {
        outgoingList.addEventListener('click', function (e) {
            const btn = e.target.closest('button.duel-cancel');
            if (!btn) return;
            const row = btn.closest('.duel-pending-row');
            if (!row) return;
            const duelId = row.dataset.duelId;
            if (!duelId) return;
            window.TobyApi.postJson('/pvp/' + guildId + '/duel/' + duelId + '/cancel', {})
                .then(function (resp) {
                    if (!resp || !resp.ok) {
                        toast('error', (resp && resp.error) || 'Cancel failed.');
                    } else {
                        toast('success', 'Cancelled.');
                    }
                    refreshOutgoing();
                })
                .catch(function () { toast('error', 'Network error.'); });
        });
    }

    if (previewBtn) {
        previewBtn.addEventListener('click', function () {
            const picker = document.getElementById('duel-opponent');
            const opponentId = picker && picker.value;
            if (!opponentId) {
                toast('error', 'Pick someone to duel first.');
                return;
            }
            const opt = picker.options[picker.selectedIndex];
            const opponentName = (opt && opt.textContent ? opt.textContent : 'Opponent').trim();
            const opponentAvatar = opt && opt.dataset ? (opt.dataset.avatar || null) : null;

            const me = main.dataset;
            const stakeRaw = parseInt(document.getElementById('duel-stake').value, 10);
            const stake = Number.isFinite(stakeRaw) && stakeRaw > 0
                ? stakeRaw
                : parseInt(main.dataset.minStake, 10) || 0;

            const row = document.createElement('div');
            row.dataset.initiatorDiscordId = me.userId || '';
            row.dataset.initiatorName = me.userName || 'You';
            if (me.userAvatar) row.dataset.initiatorAvatar = me.userAvatar;
            row.dataset.opponentDiscordId = String(opponentId);
            row.dataset.opponentName = opponentName;
            if (opponentAvatar) row.dataset.opponentAvatar = opponentAvatar;

            // Coin-flip the winner so re-clicking shows both halves of the
            // choreography. lossTribute is illustrative — the real value
            // comes from the per-guild jackpot setting on the server side,
            // which we don't surface to the client.
            const initiatorWins = Math.random() < 0.5;
            const pot = stake * 2;
            const resp = {
                winnerDiscordId: initiatorWins ? row.dataset.initiatorDiscordId : row.dataset.opponentDiscordId,
                loserDiscordId: initiatorWins ? row.dataset.opponentDiscordId : row.dataset.initiatorDiscordId,
                stake: stake,
                pot: pot,
                lossTribute: Math.max(0, Math.floor(stake * 0.1)),
            };

            playDuelResolution(row, resp);
        });
    }

    // Initial expiry pass on the server-rendered rows so the placeholder
    // "expires soon" text is replaced immediately on page load.
    tickExpiries();
    setInterval(tickExpiries, 1000);
    setInterval(refreshAll, 5000);

    // ─── RPS panel (SSE-driven, no polling) ───────────────────────────
    //
    // One EventSource feeds every PvP event for the signed-in viewer in
    // this guild. We open it once on page load. Initial state is GET-
    // fetched once on tab show; subsequent updates come from SSE events.
    initRpsPanel(document, guildId);

    // ─── Tic-Tac-Toe + Connect 4 panels ───────────────────────────────
    //
    // Both share the same turn-based shape (alternating moves with a per-
    // move shot-clock, `/move` POST taking `{move: Int}`) so one factory
    // drives both. The per-game divergence — board grid + move type
    // validation — lives in the `renderBoard` callback each passes in.
    initBoardGamePanel(document, guildId, {
        slug: 'tictactoe',
        emptyLabel: 'Tic-Tac-Toe',
        renderBoard: renderTicTacToeBoard,
    });
    initBoardGamePanel(document, guildId, {
        slug: 'connect4',
        emptyLabel: 'Connect 4',
        renderBoard: renderConnect4Board,
    });

    function initRpsPanel(doc, guildId) {
        const panel = doc.getElementById('pvp-panel-rps');
        if (!panel) return;
        const form = doc.getElementById('rps-challenge');
        const pendingList = doc.getElementById('rps-pending-list');
        const outgoingList = doc.getElementById('rps-outgoing-list');
        const activeSection = doc.getElementById('rps-active-section');
        const activeBoard = doc.getElementById('rps-active');
        if (!form || !pendingList || !outgoingList || !activeBoard) return;

        // Track the session the user is currently looking at (the live
        // match). If the SSE delivers an event for a different session,
        // it goes to the inbox / outbox instead.
        let activeSessionId = null;
        let activeSnapshot = null;
        let initialFetched = false;

        // ── Initial state on first tab show ──
        function fetchInitialState() {
            if (initialFetched) return;
            initialFetched = true;
            Promise.all([
                fetch('/pvp/' + guildId + '/rps/pending', { credentials: 'same-origin' }).then(safeJson),
                fetch('/pvp/' + guildId + '/rps/outgoing', { credentials: 'same-origin' }).then(safeJson),
                fetch('/pvp/' + guildId + '/rps/active', { credentials: 'same-origin' }).then(safeJson),
            ]).then(function (results) {
                renderPendingList(pendingList, results[0] || [], 'incoming');
                renderPendingList(outgoingList, results[1] || [], 'outgoing');
                const active = (results[2] || []);
                if (active.length > 0) openActiveSession(active[0]);
            }).catch(function () { /* network blip — SSE will catch us up */ });
        }
        // Tab show hook — fetch when the panel becomes visible. Tabs.js
        // doesn't expose an event, so we observe the `hidden` attribute.
        const observer = new MutationObserver(function () {
            if (!panel.hidden) fetchInitialState();
        });
        observer.observe(panel, { attributes: true, attributeFilter: ['hidden'] });
        if (!panel.hidden) fetchInitialState();

        // ── SSE channel — shared across all PvP games ──
        // Opened lazily so we don't burn a connection on guests who
        // never click any PvP tab. Once open, it stays open for the
        // lifetime of the page.
        let eventSource = null;
        function ensureStream() {
            if (eventSource) return;
            try {
                eventSource = new EventSource('/pvp/' + guildId + '/stream');
            } catch (e) {
                return; // no SSE support — RPS still works via GET initial-fetch on tab show
            }
            eventSource.addEventListener('rps.offered', function (e) { onOffered(parseEvent(e)); });
            eventSource.addEventListener('rps.accepted', function (e) { onAccepted(parseEvent(e)); });
            eventSource.addEventListener('rps.picked', function (e) { onOpponentPicked(parseEvent(e)); });
            eventSource.addEventListener('rps.resolved', function (e) { onResolved(parseEvent(e)); });
            eventSource.addEventListener('rps.removed', function (e) { onRemoved(parseEvent(e)); });
        }
        // Open immediately — other PvP tabs will share this connection.
        ensureStream();

        // ── Event handlers ──
        function onOffered(payload) {
            // Refetch pending list (the payload may be partial; safer to GET).
            fetch('/pvp/' + guildId + '/rps/pending', { credentials: 'same-origin' })
                .then(safeJson).then(function (rows) {
                    renderPendingList(pendingList, rows || [], 'incoming');
                });
        }
        function onAccepted(payload) {
            // Either I or my opponent accepted an offer; an active session exists now.
            // Pull the session view for the right id.
            if (!payload || !payload.sessionId) return;
            fetch('/pvp/' + guildId + '/rps/' + payload.sessionId, { credentials: 'same-origin' })
                .then(safeJson).then(function (view) { if (view) openActiveSession(view); });
            // Pending and outgoing lists need refreshing too — the offer is no longer pending.
            refreshLists();
        }
        function onOpponentPicked(payload) {
            if (!payload || !payload.sessionId) return;
            if (activeSessionId !== payload.sessionId) return;
            if (activeSnapshot) {
                activeSnapshot.opponentPicked = true;
                renderRpsBoard(activeBoard, activeSnapshot);
            }
        }
        function onResolved(payload) {
            if (!payload || !payload.sessionId) return;
            if (activeSessionId === payload.sessionId && payload.outcome) {
                renderResolution(activeBoard, payload.outcome);
                setTimeout(closeActiveSession, 6000);
            }
            refreshLists();
        }
        function onRemoved(payload) {
            if (!payload || !payload.sessionId) return;
            // If the removed one was the active match, drop it.
            if (activeSessionId === payload.sessionId) closeActiveSession();
            refreshLists();
        }

        function refreshLists() {
            fetch('/pvp/' + guildId + '/rps/pending', { credentials: 'same-origin' })
                .then(safeJson).then(function (rows) { renderPendingList(pendingList, rows || [], 'incoming'); });
            fetch('/pvp/' + guildId + '/rps/outgoing', { credentials: 'same-origin' })
                .then(safeJson).then(function (rows) { renderPendingList(outgoingList, rows || [], 'outgoing'); });
        }

        // ── Pending/outgoing list rendering ──
        function renderPendingList(container, rows, kind) {
            container.querySelectorAll('.pvp-pending-row').forEach(function (r) { r.remove(); });
            const empty = container.querySelector('[data-empty]');
            if (rows.length === 0) {
                if (empty) empty.hidden = false;
                return;
            }
            if (empty) empty.hidden = true;
            rows.forEach(function (row) {
                container.appendChild(buildPendingRow(row, kind));
            });
        }
        function buildPendingRow(row, kind) {
            const sessionId = row.sessionId;
            const participants = row.participants || {};
            const incoming = kind === 'incoming';
            const otherSide = incoming ? participants.initiator : participants.opponent;
            const div = document.createElement('div');
            div.className = 'pvp-pending-row';
            div.dataset.sessionId = String(sessionId);
            div.innerHTML =
                '<div class="pvp-pending-info">' +
                  '<div class="pvp-pending-who"><span class="muted">' + (incoming ? 'From' : 'To') + '</span> ' +
                    '<strong></strong></div>' +
                  '<div class="pvp-pending-meta"><span>for <strong></strong> credits</span></div>' +
                '</div>' +
                '<div class="pvp-pending-actions"></div>';
            div.querySelector('.pvp-pending-who strong').textContent = (otherSide && otherSide.name) || 'Unknown';
            div.querySelectorAll('strong')[1].textContent = String(participants.stake || 0);
            const actions = div.querySelector('.pvp-pending-actions');
            if (incoming) {
                actions.appendChild(makeButton('Accept', 'btn-primary', function () { acceptOffer(sessionId); }));
                actions.appendChild(makeButton('Decline', 'btn-secondary', function () { declineOffer(sessionId); }));
            } else {
                actions.appendChild(makeButton('Cancel', 'btn-secondary', function () { cancelOffer(sessionId); }));
            }
            return div;
        }

        // ── Active session ──
        function openActiveSession(view) {
            activeSessionId = view.sessionId;
            activeSnapshot = view;
            activeSection.hidden = false;
            renderRpsBoard(activeBoard, view);
        }
        function closeActiveSession() {
            activeSessionId = null;
            activeSnapshot = null;
            activeSection.hidden = true;
            activeBoard.innerHTML = '';
        }

        function renderRpsBoard(boardEl, view) {
            const opponent = view.participants && view.participants.opponent;
            const stake = (view.participants && view.participants.stake) || 0;
            boardEl.innerHTML = '';
            const header = document.createElement('p');
            header.className = 'pvp-board-header';
            header.textContent = 'vs ' + ((opponent && opponent.name) || 'opponent') + ' — stake ' + stake + ' credits';
            boardEl.appendChild(header);

            if (view.iPicked) {
                const msg = document.createElement('p');
                msg.className = 'muted';
                msg.textContent = view.opponentPicked
                    ? 'Both picked — resolving…'
                    : 'You picked. Waiting for opponent…';
                boardEl.appendChild(msg);
            } else {
                const choices = document.createElement('div');
                choices.className = 'pvp-rps-choices';
                [{ label: '✊ Rock', value: 'ROCK' }, { label: '✋ Paper', value: 'PAPER' }, { label: '✌️ Scissors', value: 'SCISSORS' }]
                    .forEach(function (c) {
                        const btn = document.createElement('button');
                        btn.type = 'button';
                        btn.className = 'pvp-rps-choice';
                        btn.textContent = c.label;
                        btn.addEventListener('click', function () { submitPick(view.sessionId, c.value); });
                        choices.appendChild(btn);
                    });
                boardEl.appendChild(choices);
                if (view.opponentPicked) {
                    const note = document.createElement('p');
                    note.className = 'muted';
                    note.textContent = 'Opponent has already picked — your move.';
                    boardEl.appendChild(note);
                }
            }

            const forfeit = makeButton('Forfeit', 'btn-secondary', function () { submitForfeit(view.sessionId); });
            forfeit.classList.add('pvp-board-forfeit');
            boardEl.appendChild(forfeit);
        }

        function renderResolution(boardEl, outcome) {
            boardEl.innerHTML = '';
            const verdict = document.createElement('h3');
            verdict.className = 'pvp-board-verdict';
            const winnerName = outcome.winnerDiscordId === activeSnapshot.participants.initiator.discordId
                ? activeSnapshot.participants.initiator.name
                : (activeSnapshot.participants.opponent && activeSnapshot.participants.opponent.name) || 'Opponent';
            if (outcome.verdict === 'WIN') {
                verdict.textContent = winnerName + ' wins — pot ' + outcome.pot;
            } else if (outcome.verdict === 'DRAW') {
                verdict.textContent = 'Draw — both picked ' + (outcome.initiatorChoice || '?').toLowerCase();
            } else {
                verdict.textContent = 'Refund — neither picked';
            }
            boardEl.appendChild(verdict);
        }

        // ── Mutations ──
        function acceptOffer(sessionId) {
            postJson('/pvp/' + guildId + '/rps/' + sessionId + '/accept', {});
        }
        function declineOffer(sessionId) {
            postJson('/pvp/' + guildId + '/rps/' + sessionId + '/decline', {});
        }
        function cancelOffer(sessionId) {
            postJson('/pvp/' + guildId + '/rps/' + sessionId + '/cancel', {});
        }
        function submitPick(sessionId, choice) {
            postJson('/pvp/' + guildId + '/rps/' + sessionId + '/pick', { choice: choice })
                .then(function (resp) {
                    if (!resp || !resp.ok) return;
                    // If this pick triggered resolution, render outcome now;
                    // otherwise reflect "waiting for opponent" locally.
                    if (resp.outcome) {
                        renderResolution(activeBoard, resp.outcome);
                        setTimeout(closeActiveSession, 6000);
                    } else if (activeSnapshot) {
                        activeSnapshot.iPicked = true;
                        renderRpsBoard(activeBoard, activeSnapshot);
                    }
                });
        }
        function submitForfeit(sessionId) {
            postJson('/pvp/' + guildId + '/rps/' + sessionId + '/forfeit', {});
        }

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const opponent = document.getElementById('rps-opponent').value;
            const stakeRaw = parseInt(document.getElementById('rps-stake').value, 10);
            if (!opponent) {
                if (window.toast) window.toast('Pick an opponent.', 'error');
                return;
            }
            postJson('/pvp/' + guildId + '/rps/challenge', {
                opponentDiscordId: opponent, stake: isFinite(stakeRaw) ? stakeRaw : 0,
            }).then(function (resp) {
                if (!resp) return;
                if (!resp.ok) {
                    if (window.toast) window.toast(resp.error || 'Challenge failed.', 'error');
                    return;
                }
                if (window.toast) window.toast('Challenge sent.', 'success');
                refreshLists();
            });
        });

        // ── Helpers ──
        function makeButton(label, cls, onClick) {
            const b = document.createElement('button');
            b.type = 'button';
            b.className = cls;
            b.textContent = label;
            b.addEventListener('click', onClick);
            return b;
        }
        function parseEvent(e) {
            try { return JSON.parse(e.data); } catch (_) { return null; }
        }
        function safeJson(r) { return r.ok ? r.json() : null; }
        function postJson(url, body) {
            return window.TobyApi
                ? window.TobyApi.postJson(url, body).catch(function () { return null; })
                : fetch(url, {
                    method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body),
                }).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
        }
    }
    // ─── Shared TTT / C4 panel factory ────────────────────────────────
    //
    // Generic turn-based board panel. Wires the challenge form, the
    // pending / outgoing list rendering, the live-match polling-via-SSE,
    // and the accept / decline / cancel / forfeit buttons. The per-game
    // board renderer (3x3 grid for TTT, 7x6 grid for C4) is passed in
    // via `opts.renderBoard(boardEl, view, ctx)` where `ctx` exposes
    // `submitMove(cellOrColumn)` and `submitForfeit()` for the per-game
    // grid to wire its click handlers to.
    function initBoardGamePanel(doc, guildId, opts) {
        const slug = opts.slug;
        const panel = doc.getElementById('pvp-panel-' + slug);
        if (!panel) return;
        const form = doc.getElementById(slug + '-challenge');
        const pendingList = doc.getElementById(slug + '-pending-list');
        const outgoingList = doc.getElementById(slug + '-outgoing-list');
        const activeSection = doc.getElementById(slug + '-active-section');
        const activeBoard = doc.getElementById(slug + '-active');
        if (!form || !pendingList || !outgoingList || !activeBoard) return;

        let activeSessionId = null;
        let activeSnapshot = null;
        let initialFetched = false;

        function fetchInitialState() {
            if (initialFetched) return;
            initialFetched = true;
            Promise.all([
                fetch('/pvp/' + guildId + '/' + slug + '/pending', { credentials: 'same-origin' }).then(safeJson),
                fetch('/pvp/' + guildId + '/' + slug + '/outgoing', { credentials: 'same-origin' }).then(safeJson),
                fetch('/pvp/' + guildId + '/' + slug + '/active', { credentials: 'same-origin' }).then(safeJson),
            ]).then(function (results) {
                renderPendingList(pendingList, results[0] || [], 'incoming');
                renderPendingList(outgoingList, results[1] || [], 'outgoing');
                const active = (results[2] || []);
                if (active.length > 0) openActiveSession(active[0]);
            }).catch(function () { /* SSE will catch us up */ });
        }
        const observer = new MutationObserver(function () {
            if (!panel.hidden) fetchInitialState();
        });
        observer.observe(panel, { attributes: true, attributeFilter: ['hidden'] });
        if (!panel.hidden) fetchInitialState();

        // The page-wide EventSource opens once for the whole /pvp page (see
        // initRpsPanel). We piggyback on that channel; SSE event names are
        // namespaced by slug so cross-tab noise stays low.
        ensureSharedStream();
        document.addEventListener('pvp.sse.' + slug + '.offered', function (e) { onOffered(e.detail); });
        document.addEventListener('pvp.sse.' + slug + '.accepted', function (e) { onAccepted(e.detail); });
        document.addEventListener('pvp.sse.' + slug + '.moved', function (e) { onMoved(e.detail); });
        document.addEventListener('pvp.sse.' + slug + '.resolved', function (e) { onResolved(e.detail); });
        document.addEventListener('pvp.sse.' + slug + '.removed', function (e) { onRemoved(e.detail); });

        function onOffered() { refreshLists(); }
        function onAccepted(payload) {
            if (!payload || !payload.sessionId) return;
            fetch('/pvp/' + guildId + '/' + slug + '/' + payload.sessionId, { credentials: 'same-origin' })
                .then(safeJson).then(function (view) { if (view) openActiveSession(view); });
            refreshLists();
        }
        function onMoved(payload) {
            if (!payload || !payload.sessionId || activeSessionId !== payload.sessionId) return;
            if (payload.view && Object.keys(payload.view).length > 0) {
                openActiveSession(payload.view);
            } else {
                // Empty view fan-out — refetch the canonical state.
                fetch('/pvp/' + guildId + '/' + slug + '/' + payload.sessionId, { credentials: 'same-origin' })
                    .then(safeJson).then(function (view) { if (view) openActiveSession(view); });
            }
        }
        function onResolved(payload) {
            if (!payload || !payload.sessionId) return;
            if (activeSessionId === payload.sessionId && payload.outcome) {
                renderBoardOutcome(activeBoard, payload.outcome, activeSnapshot);
                setTimeout(closeActiveSession, 6000);
            }
            refreshLists();
        }
        function onRemoved(payload) {
            if (!payload || !payload.sessionId) return;
            if (activeSessionId === payload.sessionId) closeActiveSession();
            refreshLists();
        }

        function refreshLists() {
            fetch('/pvp/' + guildId + '/' + slug + '/pending', { credentials: 'same-origin' })
                .then(safeJson).then(function (rows) { renderPendingList(pendingList, rows || [], 'incoming'); });
            fetch('/pvp/' + guildId + '/' + slug + '/outgoing', { credentials: 'same-origin' })
                .then(safeJson).then(function (rows) { renderPendingList(outgoingList, rows || [], 'outgoing'); });
        }

        function renderPendingList(container, rows, kind) {
            container.querySelectorAll('.pvp-pending-row').forEach(function (r) { r.remove(); });
            const empty = container.querySelector('[data-empty]');
            if (rows.length === 0) {
                if (empty) empty.hidden = false;
                return;
            }
            if (empty) empty.hidden = true;
            rows.forEach(function (row) {
                container.appendChild(buildPendingRow(row, kind));
            });
        }
        function buildPendingRow(row, kind) {
            const sessionId = row.sessionId;
            const participants = row.participants || {};
            const incoming = kind === 'incoming';
            const otherSide = incoming ? participants.initiator : participants.opponent;
            const div = document.createElement('div');
            div.className = 'pvp-pending-row';
            div.dataset.sessionId = String(sessionId);
            div.innerHTML =
                '<div class="pvp-pending-info">' +
                  '<div class="pvp-pending-who"><span class="muted">' + (incoming ? 'From' : 'To') + '</span> ' +
                    '<strong></strong></div>' +
                  '<div class="pvp-pending-meta"><span>for <strong></strong> credits</span></div>' +
                '</div>' +
                '<div class="pvp-pending-actions"></div>';
            div.querySelector('.pvp-pending-who strong').textContent = (otherSide && otherSide.name) || 'Unknown';
            div.querySelectorAll('strong')[1].textContent = String(participants.stake || 0);
            const actions = div.querySelector('.pvp-pending-actions');
            if (incoming) {
                actions.appendChild(makeButton('Accept', 'btn-primary', function () {
                    postJson('/pvp/' + guildId + '/' + slug + '/' + sessionId + '/accept', {});
                }));
                actions.appendChild(makeButton('Decline', 'btn-secondary', function () {
                    postJson('/pvp/' + guildId + '/' + slug + '/' + sessionId + '/decline', {});
                }));
            } else {
                actions.appendChild(makeButton('Cancel', 'btn-secondary', function () {
                    postJson('/pvp/' + guildId + '/' + slug + '/' + sessionId + '/cancel', {});
                }));
            }
            return div;
        }

        function openActiveSession(view) {
            activeSessionId = view.sessionId;
            activeSnapshot = view;
            activeSection.hidden = false;
            const ctx = {
                submitMove: function (moveValue) {
                    postJson('/pvp/' + guildId + '/' + slug + '/' + view.sessionId + '/move', { move: moveValue })
                        .then(function (resp) {
                            if (!resp) return;
                            if (!resp.ok) {
                                if (window.toast) window.toast(resp.error || 'Move failed.', 'error');
                                return;
                            }
                            if (resp.outcome) {
                                renderBoardOutcome(activeBoard, resp.outcome, activeSnapshot);
                                setTimeout(closeActiveSession, 6000);
                            }
                            // Continued moves: opponent gets the SSE update; our local board
                            // refreshes on the next onMoved fan-out (or the immediate /move
                            // response). Refetch for safety.
                            else {
                                fetch('/pvp/' + guildId + '/' + slug + '/' + view.sessionId, { credentials: 'same-origin' })
                                    .then(safeJson).then(function (v) { if (v) openActiveSession(v); });
                            }
                        });
                },
                submitForfeit: function () {
                    postJson('/pvp/' + guildId + '/' + slug + '/' + view.sessionId + '/forfeit', {});
                },
            };
            opts.renderBoard(activeBoard, view, ctx);
        }
        function closeActiveSession() {
            activeSessionId = null;
            activeSnapshot = null;
            activeSection.hidden = true;
            activeBoard.innerHTML = '';
        }

        function renderBoardOutcome(boardEl, outcome, snapshot) {
            boardEl.innerHTML = '';
            const verdict = document.createElement('h3');
            verdict.className = 'pvp-board-verdict';
            if (outcome.verdict === 'WIN') {
                const initiator = snapshot && snapshot.participants && snapshot.participants.initiator;
                const opponent = snapshot && snapshot.participants && snapshot.participants.opponent;
                const winnerName = (initiator && outcome.winnerDiscordId === initiator.discordId)
                    ? initiator.name
                    : (opponent && opponent.name) || 'Opponent';
                verdict.textContent = winnerName + ' wins — pot ' + outcome.pot;
            } else if (outcome.verdict === 'DRAW') {
                verdict.textContent = 'Draw — stakes refunded';
            } else {
                verdict.textContent = 'Refund';
            }
            boardEl.appendChild(verdict);
        }

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            const opponent = document.getElementById(slug + '-opponent').value;
            const stakeRaw = parseInt(document.getElementById(slug + '-stake').value, 10);
            if (!opponent) {
                if (window.toast) window.toast('Pick an opponent.', 'error');
                return;
            }
            postJson('/pvp/' + guildId + '/' + slug + '/challenge', {
                opponentDiscordId: opponent, stake: isFinite(stakeRaw) ? stakeRaw : 0,
            }).then(function (resp) {
                if (!resp) return;
                if (!resp.ok) {
                    if (window.toast) window.toast(resp.error || 'Challenge failed.', 'error');
                    return;
                }
                if (window.toast) window.toast('Challenge sent.', 'success');
                refreshLists();
            });
        });
    }

    // The page opens one EventSource (in initRpsPanel above). We
    // re-broadcast each named SSE event as a DOM CustomEvent so multiple
    // panels (initRpsPanel + the two initBoardGamePanel calls) can each
    // attach listeners without needing direct EventSource access.
    let sharedStreamOpened = false;
    function ensureSharedStream() {
        if (sharedStreamOpened) return;
        sharedStreamOpened = true;
        const main = document.getElementById('main');
        if (!main || !main.dataset.guildId) return;
        const eventSource = (function () {
            try { return new EventSource('/pvp/' + main.dataset.guildId + '/stream'); } catch (_) { return null; }
        })();
        if (!eventSource) return;
        ['tictactoe.offered','tictactoe.accepted','tictactoe.moved','tictactoe.resolved','tictactoe.removed',
         'connect4.offered','connect4.accepted','connect4.moved','connect4.resolved','connect4.removed']
            .forEach(function (name) {
                eventSource.addEventListener(name, function (e) {
                    let detail = null;
                    try { detail = JSON.parse(e.data); } catch (_) {}
                    document.dispatchEvent(new CustomEvent('pvp.sse.' + name, { detail: detail }));
                });
            });
    }

    function renderTicTacToeBoard(boardEl, view, ctx) {
        boardEl.innerHTML = '';
        const header = document.createElement('p');
        header.className = 'pvp-board-header';
        const oppName = view.participants && view.participants.opponent && view.participants.opponent.name;
        header.textContent = 'vs ' + (oppName || 'opponent') +
            ' — stake ' + ((view.participants && view.participants.stake) || 0) + ' credits' +
            (view.myTurn ? ' — your turn' : ' — waiting for opponent');
        boardEl.appendChild(header);

        const grid = document.createElement('div');
        grid.className = 'pvp-ttt-grid';
        const cells = view.cells || new Array(9).fill(null);
        const winning = new Set(view.winningLine || []);
        for (let i = 0; i < 9; i++) {
            const cell = document.createElement('button');
            cell.type = 'button';
            cell.className = 'pvp-ttt-cell';
            const mark = cells[i];
            if (mark) {
                cell.classList.add('is-' + mark.toLowerCase());
                cell.textContent = mark === 'X' ? '✕' : '○';
                cell.disabled = true;
            } else if (!view.myTurn) {
                cell.disabled = true;
            } else {
                cell.addEventListener('click', function () { ctx.submitMove(i); });
            }
            if (winning.has(i)) cell.classList.add('is-winning');
            grid.appendChild(cell);
        }
        boardEl.appendChild(grid);

        const forfeit = makeButton('Forfeit', 'btn-secondary', ctx.submitForfeit);
        forfeit.classList.add('pvp-board-forfeit');
        boardEl.appendChild(forfeit);
    }

    function renderConnect4Board(boardEl, view, ctx) {
        boardEl.innerHTML = '';
        const header = document.createElement('p');
        header.className = 'pvp-board-header';
        const oppName = view.participants && view.participants.opponent && view.participants.opponent.name;
        header.textContent = 'vs ' + (oppName || 'opponent') +
            ' — stake ' + ((view.participants && view.participants.stake) || 0) + ' credits' +
            (view.myTurn ? ' — your turn (pick a column)' : ' — waiting for opponent');
        boardEl.appendChild(header);

        // Column-drop buttons across the top. Disabled when not myTurn or
        // when the column is full (top cell of column is non-null).
        const cells = view.cells || new Array(42).fill(null);
        const dropRow = document.createElement('div');
        dropRow.className = 'pvp-c4-drop-row';
        for (let col = 0; col < 7; col++) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'pvp-c4-drop';
            btn.textContent = '↓';
            btn.dataset.col = String(col);
            const colFull = cells[col] !== null && cells[col] !== undefined; // top row of column = index col
            if (!view.myTurn || colFull) btn.disabled = true;
            else btn.addEventListener('click', function () { ctx.submitMove(col); });
            dropRow.appendChild(btn);
        }
        boardEl.appendChild(dropRow);

        const grid = document.createElement('div');
        grid.className = 'pvp-c4-grid';
        const winning = new Set(view.winningLine || []);
        for (let i = 0; i < 42; i++) {
            const cell = document.createElement('div');
            cell.className = 'pvp-c4-cell';
            const mark = cells[i];
            if (mark) cell.classList.add('is-' + mark.toLowerCase());
            if (winning.has(i)) cell.classList.add('is-winning');
            grid.appendChild(cell);
        }
        boardEl.appendChild(grid);

        const forfeit = makeButton('Forfeit', 'btn-secondary', ctx.submitForfeit);
        forfeit.classList.add('pvp-board-forfeit');
        boardEl.appendChild(forfeit);
    }

    function makeButton(label, cls, onClick) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = cls;
        b.textContent = label;
        b.addEventListener('click', onClick);
        return b;
    }
    function safeJson(r) { return r.ok ? r.json() : null; }
    function postJson(url, body) {
        return window.TobyApi
            ? window.TobyApi.postJson(url, body).catch(function () { return null; })
            : fetch(url, {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            }).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });
    }
})(typeof window !== 'undefined' ? window : null);
