// Voice tab: per-channel mute/unmute, bulk member move.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, postJson } = ctx;

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

    const moveForm = document.querySelector('.move-form');
    if (moveForm) {
        moveForm.addEventListener('submit', e => {
            e.preventDefault();
            const targetChannelId = moveForm.elements.targetChannelId.value;
            const memberIds = Array.from(moveForm.elements.memberIds.selectedOptions).map(o => o.value);
            if (!targetChannelId || memberIds.length === 0) {
                toast('Pick a destination and at least one member.', 'error');
                return;
            }
            const submitBtn = moveForm.querySelector('button[type="submit"]');
            submitBtn.disabled = true;
            postJson('/moderation/' + guildId + '/voice/move', {
                targetChannelId: targetChannelId,
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
})();
