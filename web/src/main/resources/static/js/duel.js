// /duel — challenge form posts to /duel/{guildId}/challenge; the
// inbox panel polls /duel/{guildId}/pending every 5 seconds and posts
// to /accept or /decline via the shared CSRF-aware fetch wrapper.
(function () {
    'use strict';

    const main = document.getElementById('main');
    if (!main) return;
    const guildId = main.dataset.guildId;
    if (!guildId) return;

    const balanceEl = document.getElementById('duel-balance');
    const challengeForm = document.getElementById('duel-challenge');
    const pendingList = document.getElementById('duel-pending-list');

    function showToast(level, msg) {
        if (window.TobyToasts && window.TobyToasts[level]) window.TobyToasts[level](msg);
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
            const node = document.createElement('div');
            node.className = 'duel-pending-row';
            node.dataset.duelId = String(row.duelId);
            node.dataset.stake = String(row.stake);
            node.innerHTML =
                '<div><span>From <strong>' + row.initiatorDiscordId + '</strong></span>' +
                '<span> for <strong>' + row.stake + '</strong> credits</span></div>' +
                '<div class="duel-pending-actions">' +
                '<button class="btn-primary duel-accept" type="button">Accept</button>' +
                '<button class="btn-secondary duel-decline" type="button">Decline</button>' +
                '</div>';
            pendingList.appendChild(node);
        });
    }

    function refreshPending() {
        fetch('/duel/' + guildId + '/pending', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(renderPending)
            .catch(function () { /* keep last known state */ });
    }

    if (challengeForm) {
        challengeForm.addEventListener('submit', function (e) {
            e.preventDefault();
            const opponent = parseInt(document.getElementById('duel-opponent').value, 10);
            const stake = parseInt(document.getElementById('duel-stake').value, 10);
            if (!opponent || !stake) {
                showToast('error', 'Opponent and stake are required.');
                return;
            }
            window.TobyApi.postJson('/duel/' + guildId + '/challenge', {
                opponentDiscordId: opponent,
                stake: stake
            }).then(function (resp) {
                if (!resp || !resp.ok) {
                    showToast('error', (resp && resp.error) || 'Challenge failed.');
                    return;
                }
                showToast('success', 'Challenge sent. Waiting for them to accept (60s window).');
            }).catch(function () { showToast('error', 'Network error sending challenge.'); });
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
                    showToast('error', (resp && resp.error) || 'Action failed.');
                    refreshPending();
                    return;
                }
                if (isAccept && resp.winnerDiscordId) {
                    const youWon = resp.winnerNewBalance != null && resp.loserNewBalance != null;
                    showToast('success',
                        'Resolved: <@' + resp.winnerDiscordId + '> won the ' + resp.pot +
                        ' pot (' + resp.lossTribute + ' to jackpot).');
                    if (balanceEl) {
                        // We don't know which side we are without comparing, but the
                        // page will refresh the next poll cycle.
                    }
                } else if (isDecline) {
                    showToast('success', 'Declined.');
                }
                refreshPending();
            }).catch(function () { showToast('error', 'Network error.'); });
        });
    }

    setInterval(refreshPending, 5000);
})();
