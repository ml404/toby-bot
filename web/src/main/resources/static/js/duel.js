// /duel — challenge form posts to /duel/{guildId}/challenge; the
// inbox panel polls /duel/{guildId}/pending every 5 seconds and posts
// to /accept or /decline via the shared CSRF-aware fetch wrapper.
(function () {
    'use strict';

    const main = document.getElementById('main');
    if (!main) return;
    const guildId = main.dataset.guildId;
    if (!guildId) return;
    const ttlSeconds = parseInt(main.dataset.ttlSeconds, 10) || 0;

    const balanceEl = document.getElementById('duel-balance');
    const challengeForm = document.getElementById('duel-challenge');
    const pendingList = document.getElementById('duel-pending-list');
    const outgoingList = document.getElementById('duel-outgoing-list');

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
        // of the cell — duel.html relies on Thymeleaf escaping for the same
        // reason, so the dynamic path needs the same guarantee.
        nameEl.textContent = name || '';
        cell.appendChild(nameEl);
        return cell;
    }

    function formatExpiry(createdAtSeconds) {
        if (!createdAtSeconds || !ttlSeconds) return '';
        const expiresAtMs = (createdAtSeconds + ttlSeconds) * 1000;
        const remaining = Math.max(0, Math.round((expiresAtMs - Date.now()) / 1000));
        if (remaining <= 0) return 'expiring…';
        if (remaining < 60) return 'expires in ' + remaining + 's';
        const minutes = Math.floor(remaining / 60);
        const seconds = remaining % 60;
        return seconds > 0
            ? 'expires in ' + minutes + 'm ' + seconds + 's'
            : 'expires in ' + minutes + 'm';
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
        expiry.textContent = formatExpiry(row.createdAtEpochSeconds);
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
        fetch('/duel/' + guildId + '/pending', { credentials: 'same-origin' })
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
        fetch('/duel/' + guildId + '/outgoing', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(renderOutgoing)
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
            if (label) label.textContent = formatExpiry(createdAt);
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
            window.TobyApi.postJson('/duel/' + guildId + '/challenge', {
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

            const path = '/duel/' + guildId + '/' + duelId + (isAccept ? '/accept' : '/decline');
            window.TobyApi.postJson(path, {}).then(function (resp) {
                if (!resp || !resp.ok) {
                    toast('error', (resp && resp.error) || 'Action failed.');
                    refreshAll();
                    return;
                }
                if (isAccept && resp.winnerDiscordId) {
                    playDuelResolution(row, resp);
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
            window.TobyApi.postJson('/duel/' + guildId + '/' + duelId + '/cancel', {})
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

    function makeFigure(name, avatarUrl, side) {
        const fig = document.createElement('div');
        fig.className = 'duel-figure duel-figure--' + side;
        const av = document.createElement('div');
        av.className = 'duel-figure-avatar';
        if (avatarUrl) {
            const img = document.createElement('img');
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
        const cap = document.createElement('div');
        cap.className = 'duel-figure-name';
        cap.textContent = name || 'Unknown';
        fig.appendChild(cap);
        return fig;
    }

    function playDuelResolution(row, resp) {
        const initiatorName = row.dataset.initiatorName || 'Challenger';
        const initiatorAvatar = row.dataset.initiatorAvatar || null;
        const opponentName = row.dataset.opponentName || 'You';
        const opponentAvatar = row.dataset.opponentAvatar || null;
        const winnerId = resp.winnerDiscordId;
        const initiatorWon = winnerId === row.dataset.initiatorDiscordId;
        const winnerName = initiatorWon ? initiatorName : opponentName;

        const reduceMotion = window.matchMedia &&
            window.matchMedia('(prefers-reduced-motion: reduce)').matches;

        const overlay = document.createElement('div');
        overlay.className = 'duel-resolution-overlay';
        if (reduceMotion) overlay.classList.add('is-reduced');
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-label', 'Duel result');

        const arena = document.createElement('div');
        arena.className = 'duel-arena';

        const left = makeFigure(initiatorName, initiatorAvatar, 'left');
        const right = makeFigure(opponentName, opponentAvatar, 'right');
        if (initiatorWon) left.classList.add('is-winner'); else left.classList.add('is-loser');
        if (initiatorWon) right.classList.add('is-loser'); else right.classList.add('is-winner');

        const flash = document.createElement('div');
        flash.className = 'duel-flash';
        flash.textContent = '💥';

        arena.appendChild(left);
        arena.appendChild(flash);
        arena.appendChild(right);
        overlay.appendChild(arena);

        const pill = document.createElement('div');
        pill.className = 'duel-credits-pill';
        pill.textContent = '+' + resp.pot + ' credits';
        if (initiatorWon) pill.classList.add('flies-left'); else pill.classList.add('flies-right');
        overlay.appendChild(pill);

        const resultLine = document.createElement('div');
        resultLine.className = 'duel-result-line';
        resultLine.textContent = 'Winner: ' + winnerName + ' took ' + resp.pot +
            ' credits (' + resp.lossTribute + ' to jackpot)';
        overlay.appendChild(resultLine);

        const hint = document.createElement('div');
        hint.className = 'duel-dismiss-hint';
        hint.textContent = 'Click anywhere or press Esc to continue';
        overlay.appendChild(hint);

        let dismissed = false;
        function dismiss() {
            if (dismissed) return;
            dismissed = true;
            clearTimeout(autoDismiss);
            document.removeEventListener('keydown', onKey);
            overlay.classList.add('is-dismissing');
            // Brief fade-out so it doesn't pop off.
            setTimeout(function () {
                if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
                refreshAll();
            }, 200);
        }
        function onKey(e) { if (e.key === 'Escape') dismiss(); }
        overlay.addEventListener('click', dismiss);
        document.addEventListener('keydown', onKey);
        const autoDismiss = setTimeout(dismiss, 6000);

        document.body.appendChild(overlay);
    }

    // Initial expiry pass on the server-rendered rows so the placeholder
    // "expires soon" text is replaced immediately on page load.
    tickExpiries();
    setInterval(tickExpiries, 1000);
    setInterval(refreshAll, 5000);
})();
