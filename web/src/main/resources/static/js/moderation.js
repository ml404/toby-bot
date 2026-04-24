(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const isOwner = main.dataset.isOwner === 'true';
    const actorId = main.dataset.actorId;

    const postJson = window.TobyApi.postJson;
    const deleteJson = (window.TobyApi && window.TobyApi.deleteJson) || function (url) {
        return fetch(url, { method: 'DELETE', headers: { 'Accept': 'application/json' } })
            .then(r => r.json().catch(() => ({ ok: r.ok })));
    };

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
            console.debug('[config save]', { key: key, value: value });
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/config', { key: key, value: value })
                .then(r => {
                    btn.disabled = false;
                    console.debug('[config save response]', { key: key, response: r });
                    if (r && r.ok) {
                        toast('Config saved.', 'success');
                        // Reflect the saved value as the new default so a refresh
                        // doesn't look like "nothing happened" even if the read path
                        // is cached stale somewhere.
                        if (input.tagName === 'SELECT') {
                            Array.from(input.options).forEach(o => o.defaultSelected = (o.value === value));
                        } else {
                            input.defaultValue = value;
                        }
                    } else {
                        toast(r?.error || 'Could not save config.', 'error');
                    }
                })
                .catch((err) => {
                    btn.disabled = false;
                    console.error('[config save error]', { key: key, err: err });
                    toast('Network error.', 'error');
                });
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

    // --- Titles tab ---
    document.querySelectorAll('.title-buy').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const titleId = row.dataset.titleId;
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/titles/' + titleId + '/buy', {})
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        toast('Title purchased. Reload to equip.', 'success');
                        setTimeout(() => window.location.reload(), 600);
                    } else {
                        toast(r?.error || 'Could not buy.', 'error');
                    }
                })
                .catch(() => { btn.disabled = false; toast('Network error.', 'error'); });
        });
    });

    document.querySelectorAll('.title-equip').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const titleId = row.dataset.titleId;
            btn.disabled = true;
            postJson('/moderation/' + guildId + '/titles/' + titleId + '/equip', {})
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        toast('Title equipped.', 'success');
                        setTimeout(() => window.location.reload(), 600);
                    } else {
                        toast(r?.error || 'Could not equip.', 'error');
                    }
                })
                .catch(() => { btn.disabled = false; toast('Network error.', 'error'); });
        });
    });

    document.querySelectorAll('.title-unequip').forEach(btn => {
        btn.addEventListener('click', () => {
            btn.disabled = true;
            deleteJson('/moderation/' + guildId + '/titles/equipped')
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        toast('Title unequipped.', 'success');
                        setTimeout(() => window.location.reload(), 600);
                    } else {
                        toast(r?.error || 'Could not unequip.', 'error');
                    }
                })
                .catch(() => { btn.disabled = false; toast('Network error.', 'error'); });
        });
    });

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
