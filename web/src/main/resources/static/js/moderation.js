(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const isOwner = main.dataset.isOwner === 'true';
    const actorId = main.dataset.actorId;
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || '';
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';

    function postJson(url, body) {
        const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
        if (csrfToken) headers[csrfHeader] = csrfToken;
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers,
            body: JSON.stringify(body)
        }).then(r => r.json().catch(() => ({ ok: r.ok, error: r.ok ? null : 'Request failed.' })));
    }

    function toast(msg, type) {
        if (window.TobyToast && typeof window.TobyToast.show === 'function') {
            window.TobyToast.show(msg, { type: type || 'info' });
        } else {
            console.log('[' + (type || 'info') + '] ' + msg);
        }
    }

    // --- Tabs ---
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const tab = btn.dataset.tab;
            document.querySelectorAll('.tab-btn').forEach(b => {
                const active = b === btn;
                b.classList.toggle('active', active);
                b.setAttribute('aria-selected', active ? 'true' : 'false');
            });
            document.querySelectorAll('.tab-panel').forEach(p => {
                p.classList.toggle('active', p.dataset.panel === tab);
            });
        });
    });

    // --- Users tab: permission toggles ---
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

    // --- Users tab: initiative modifier (commit on blur or Enter) ---
    document.querySelectorAll('.initiative-input').forEach(input => {
        let lastValue = input.value;
        const commit = () => {
            const val = parseInt(input.value, 10);
            if (Number.isNaN(val) || val === parseInt(lastValue, 10)) {
                input.value = lastValue;
                return;
            }
            const memberId = input.closest('tr').dataset.memberId;
            input.disabled = true;
            postJson('/moderation/' + guildId + '/user/' + memberId + '/initiative', { modifier: val })
                .then(r => {
                    input.disabled = false;
                    if (r && r.ok) {
                        lastValue = String(val);
                        toast('Initiative modifier saved.', 'success');
                    } else {
                        input.value = lastValue;
                        toast(r?.error || 'Could not save modifier.', 'error');
                    }
                })
                .catch(() => { input.disabled = false; input.value = lastValue; toast('Network error.', 'error'); });
        };
        input.addEventListener('blur', commit);
        input.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); input.blur(); } });
    });

    // --- Users tab: kick ---
    document.querySelectorAll('.kick-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const memberId = row.dataset.memberId;
            const name = btn.dataset.name || 'this member';
            const reason = prompt('Kick ' + name + '? Optional reason:');
            if (reason === null) return;
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/kick', {
                targetDiscordId: Number(memberId),
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

    // --- Config tab ---
    document.querySelectorAll('.config-row').forEach(row => {
        const key = row.dataset.key;
        const input = row.querySelector('input, select');
        const btn = row.querySelector('.save-config');
        if (!btn || !input) return;
        btn.addEventListener('click', () => {
            const value = (input.value || '').toString();
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/config', { key: key, value: value })
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) toast('Config saved.', 'success');
                    else toast(r?.error || 'Could not save config.', 'error');
                })
                .catch(() => { btn.disabled = false; toast('Network error.', 'error'); });
        });
    });

    // --- Social credit tab ---
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

    // --- Voice tab: mute/unmute ---
    document.querySelectorAll('.mute-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const card = btn.closest('.voice-card');
            const channelId = card.dataset.channelId;
            const mute = btn.dataset.mute === 'true';
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/voice/' + channelId + '/mute', { mute: mute })
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        const label = mute ? 'muted' : 'unmuted';
                        const n = (r.changed || []).length;
                        toast(label + ' ' + n + ' member' + (n === 1 ? '' : 's') + '.', 'success');
                        if (r.skipped && r.skipped.length) toast('Skipped: ' + r.skipped.join(', '), 'info');
                    } else {
                        toast(r?.error || 'Could not change mute.', 'error');
                    }
                })
                .catch(() => { btn.disabled = false; toast('Network error.', 'error'); });
        });
    });

    // --- Voice tab: move form ---
    const moveForm = document.querySelector('.move-form');
    if (moveForm) {
        moveForm.addEventListener('submit', e => {
            e.preventDefault();
            const targetChannelId = moveForm.elements.targetChannelId.value;
            const memberIds = Array.from(moveForm.elements.memberIds.selectedOptions).map(o => Number(o.value));
            if (!targetChannelId || memberIds.length === 0) {
                toast('Pick a destination and at least one member.', 'error');
                return;
            }
            const submitBtn = moveForm.querySelector('button[type="submit"]');
            submitBtn.disabled = true;
            postJson('/moderation/' + guildId + '/voice/move', {
                targetChannelId: Number(targetChannelId),
                memberIds: memberIds
            }).then(r => {
                submitBtn.disabled = false;
                if (r && r.ok) {
                    const n = (r.moved || []).length;
                    toast('Moved ' + n + ' member' + (n === 1 ? '' : 's') + '.', 'success');
                    if (r.skipped && r.skipped.length) toast('Skipped: ' + r.skipped.join(', '), 'info');
                } else {
                    toast(r?.error || 'Could not move.', 'error');
                }
            }).catch(() => { submitBtn.disabled = false; toast('Network error.', 'error'); });
        });
    }

    // --- Poll tab ---
    const pollForm = document.querySelector('.poll-form');
    if (pollForm) {
        pollForm.addEventListener('submit', e => {
            e.preventDefault();
            const channelId = pollForm.elements.channelId.value;
            const question = pollForm.elements.question.value.trim();
            const options = Array.from(pollForm.querySelectorAll('.poll-option'))
                .map(i => i.value.trim())
                .filter(v => v.length > 0);
            if (!channelId || !question || options.length < 2) {
                toast('Need a channel, a question, and at least 2 options.', 'error');
                return;
            }
            const submitBtn = pollForm.querySelector('button[type="submit"]');
            submitBtn.disabled = true;
            postJson('/moderation/' + guildId + '/poll', {
                channelId: Number(channelId),
                question: question,
                options: options
            }).then(r => {
                submitBtn.disabled = false;
                if (r && r.ok) {
                    toast('Poll posted.', 'success');
                    pollForm.reset();
                } else {
                    toast(r?.error || 'Could not post poll.', 'error');
                }
            }).catch(() => { submitBtn.disabled = false; toast('Network error.', 'error'); });
        });
    }
})();
