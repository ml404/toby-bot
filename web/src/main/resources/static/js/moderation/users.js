// Users tab: client-side member search, permission toggles, kick, social-credit adjust.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, isOwner, actorId, postJson } = ctx;

    // --- Client-side member search filter ---
    (function setupUserSearch() {
        const input = document.getElementById('user-search');
        const count = document.getElementById('user-search-count');
        const usersPanel = document.querySelector('.mod-panel[data-panel="users"]');
        if (!input || !usersPanel) return;

        // Cache each row's lowercased display name so we don't touch the DOM
        // per keystroke.
        const rows = Array.from(usersPanel.querySelectorAll('.mod-table tbody tr[data-member-id]'));
        const nameByRow = new Map(
            rows.map(tr => {
                const nameCell = tr.querySelector('.member-cell span');
                return [tr, (nameCell?.textContent || '').trim().toLowerCase()];
            })
        );
        const total = rows.length;

        function apply(query) {
            const q = query.trim().toLowerCase();
            let shown = 0;
            rows.forEach(tr => {
                const hit = q === '' || (nameByRow.get(tr) || '').includes(q);
                tr.classList.toggle('is-hidden', !hit);
                if (hit) shown++;
            });
            if (count) {
                count.textContent = q === '' ? '' : shown + ' of ' + total;
            }
        }

        input.addEventListener('input', () => apply(input.value));
        input.addEventListener('keydown', e => {
            if (e.key === 'Escape') { input.value = ''; apply(''); }
        });
    })();

    // --- Permission toggles ---
    document.querySelectorAll('.toggle-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const memberId = row.dataset.memberId;
            if (memberId === actorId) {
                toast("You can't change your own permissions.", 'error');
                return;
            }
            if (btn.dataset.ownerOnly === 'true' && !isOwner) {
                toast('Only the server owner can toggle SUPERUSER.', 'error');
                return;
            }
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/user/' + memberId + '/permission', {
                permission: btn.dataset.permission
            }).then(r => {
                btn.disabled = false;
                if (r && r.ok) {
                    const on = !btn.classList.contains('on');
                    btn.classList.toggle('on', on);
                    btn.textContent = on ? 'On' : 'Off';
                    toast('Permission updated.', 'success');
                } else {
                    toast(r?.error || 'Could not update permission.', 'error');
                }
            }).catch(() => {
                btn.disabled = false;
                toast('Network error.', 'error');
            });
        });
    });

    // --- Kick ---
    document.querySelectorAll('.kick-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const memberId = row.dataset.memberId;
            const name = btn.dataset.name || 'this member';
            const reason = prompt('Kick ' + name + '? Optional reason:');
            if (reason === null) return;
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/kick', {
                targetDiscordId: memberId,
                reason: reason || null
            }).then(r => {
                btn.disabled = false;
                if (r && r.ok) {
                    toast('Kicked ' + name + '.', 'success');
                    row.remove();
                } else {
                    toast(r?.error || 'Could not kick.', 'error');
                }
            }).catch(() => { btn.disabled = false; toast('Network error.', 'error'); });
        });
    });

    // --- Social credit adjust ---
    document.querySelectorAll('.sc-apply').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const memberId = row.dataset.memberId;
            const input = row.querySelector('.sc-adjust input');
            const delta = parseInt(input.value, 10);
            if (Number.isNaN(delta) || delta === 0) {
                toast('Enter a non-zero delta.', 'error');
                return;
            }
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/user/' + memberId + '/social-credit', { delta: delta })
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        const scoreCell = row.querySelector('.sc-score');
                        if (scoreCell) {
                            const current = parseInt(scoreCell.textContent, 10) || 0;
                            scoreCell.textContent = String(current + delta);
                        }
                        input.value = '0';
                        toast('Updated social credit.', 'success');
                    } else {
                        toast(r?.error || 'Could not adjust.', 'error');
                    }
                })
                .catch(() => { btn.disabled = false; toast('Network error.', 'error'); });
        });
    });
})();
