// Actions tab: ban / unban / timeout / untimeout / purge / lock / slowmode.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, postJson } = ctx;

    function disableSubmit(form, busy) {
        form.querySelectorAll('button').forEach(b => { b.disabled = busy; });
    }

    function handleApiResult(form, r, successMsg) {
        if (r && r.ok) {
            toast(successMsg, 'success');
            form.reset();
        } else {
            toast(r?.error || 'Failed.', 'error');
        }
    }

    function pickerValue(form, name) {
        const el = form.querySelector('[name="' + name + '"]');
        if (!el) return '';
        if (el.tagName === 'SELECT' && el.multiple) {
            return Array.from(el.selectedOptions).map(o => o.value);
        }
        return (el.value || '').trim();
    }

    document.querySelectorAll('.mod-action-form[data-action="ban"]').forEach(form => {
        form.addEventListener('submit', e => {
            e.preventDefault();
            const targetDiscordId = pickerValue(form, 'targetDiscordId');
            if (!targetDiscordId) { toast('Pick a member.', 'error'); return; }
            const reason = (form.elements.reason?.value || '').trim() || null;
            const deleteDays = parseInt(form.elements.deleteDays?.value || '0', 10) || 0;
            if (!confirm('Ban this member?')) return;
            disableSubmit(form, true);
            postJson('/moderation/' + guildId + '/ban', { targetDiscordId, reason, deleteDays })
                .then(r => { disableSubmit(form, false); handleApiResult(form, r, 'Banned.'); })
                .catch(() => { disableSubmit(form, false); toast('Network error.', 'error'); });
        });
    });

    document.querySelectorAll('.mod-action-form[data-action="unban"]').forEach(form => {
        form.addEventListener('submit', e => {
            e.preventDefault();
            const targetDiscordId = (form.elements.targetDiscordId.value || '').trim();
            if (!/^[0-9]+$/.test(targetDiscordId)) { toast('User ID must be numeric.', 'error'); return; }
            disableSubmit(form, true);
            postJson('/moderation/' + guildId + '/unban', { targetDiscordId })
                .then(r => { disableSubmit(form, false); handleApiResult(form, r, 'Unbanned.'); })
                .catch(() => { disableSubmit(form, false); toast('Network error.', 'error'); });
        });
    });

    document.querySelectorAll('.mod-action-form[data-action="timeout"]').forEach(form => {
        form.addEventListener('submit', e => {
            e.preventDefault();
            const targetDiscordId = pickerValue(form, 'targetDiscordId');
            if (!targetDiscordId) { toast('Pick a member.', 'error'); return; }
            const minutes = parseInt(form.elements.minutes.value, 10);
            if (!Number.isFinite(minutes) || minutes < 1) { toast('Minutes must be >= 1.', 'error'); return; }
            const reason = (form.elements.reason?.value || '').trim() || null;
            disableSubmit(form, true);
            postJson('/moderation/' + guildId + '/timeout', { targetDiscordId, minutes, reason })
                .then(r => { disableSubmit(form, false); handleApiResult(form, r, 'Timed out.'); })
                .catch(() => { disableSubmit(form, false); toast('Network error.', 'error'); });
        });
    });

    document.querySelectorAll('.mod-action-form[data-action="untimeout"]').forEach(form => {
        form.addEventListener('submit', e => {
            e.preventDefault();
            const targetDiscordId = pickerValue(form, 'targetDiscordId');
            if (!targetDiscordId) { toast('Pick a member.', 'error'); return; }
            disableSubmit(form, true);
            postJson('/moderation/' + guildId + '/untimeout', { targetDiscordId })
                .then(r => { disableSubmit(form, false); handleApiResult(form, r, 'Removed timeout.'); })
                .catch(() => { disableSubmit(form, false); toast('Network error.', 'error'); });
        });
    });

    document.querySelectorAll('.mod-action-form[data-action="purge"]').forEach(form => {
        form.addEventListener('submit', e => {
            e.preventDefault();
            const channelId = form.elements.channelId.value;
            const count = parseInt(form.elements.count.value, 10);
            const filterUserId = pickerValue(form, 'filterUserId') || null;
            if (!channelId) { toast('Pick a channel.', 'error'); return; }
            if (!Number.isFinite(count) || count < 1 || count > 100) {
                toast('Count must be 1-100.', 'error'); return;
            }
            if (!confirm('Delete the last ' + count + ' message(s)' + (filterUserId ? ' from that user?' : '?'))) return;
            disableSubmit(form, true);
            postJson('/moderation/' + guildId + '/purge', { channelId, count, filterUserId })
                .then(r => {
                    disableSubmit(form, false);
                    if (r && r.ok) toast('Deleted ' + r.deleted + ' message(s).', 'success');
                    else toast(r?.error || 'Failed.', 'error');
                })
                .catch(() => { disableSubmit(form, false); toast('Network error.', 'error'); });
        });
    });

    document.querySelectorAll('.mod-action-form[data-action="lock"]').forEach(form => {
        form.querySelectorAll('button[data-lock]').forEach(btn => {
            btn.addEventListener('click', () => {
                const channelId = form.elements.channelId.value;
                if (!channelId) { toast('Pick a channel.', 'error'); return; }
                const lock = btn.dataset.lock === 'true';
                disableSubmit(form, true);
                postJson('/moderation/' + guildId + '/channel/' + channelId + '/lock', { lock })
                    .then(r => {
                        disableSubmit(form, false);
                        if (r && r.ok) toast(lock ? 'Locked.' : 'Unlocked.', 'success');
                        else toast(r?.error || 'Failed.', 'error');
                    })
                    .catch(() => { disableSubmit(form, false); toast('Network error.', 'error'); });
            });
        });
    });

    document.querySelectorAll('.mod-action-form[data-action="slowmode"]').forEach(form => {
        form.addEventListener('submit', e => {
            e.preventDefault();
            const channelId = form.elements.channelId.value;
            const seconds = parseInt(form.elements.seconds.value, 10);
            if (!channelId) { toast('Pick a channel.', 'error'); return; }
            if (!Number.isFinite(seconds) || seconds < 0 || seconds > 21600) {
                toast('Seconds must be 0-21600.', 'error'); return;
            }
            disableSubmit(form, true);
            postJson('/moderation/' + guildId + '/channel/' + channelId + '/slowmode', { seconds })
                .then(r => {
                    disableSubmit(form, false);
                    if (r && r.ok) toast(seconds === 0 ? 'Slowmode disabled.' : 'Slowmode set.', 'success');
                    else toast(r?.error || 'Failed.', 'error');
                })
                .catch(() => { disableSubmit(form, false); toast('Network error.', 'error'); });
        });
    });
})();
