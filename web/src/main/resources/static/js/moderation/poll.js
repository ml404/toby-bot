// Poll tab: post an emoji-reaction poll to a text channel.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, postJson } = ctx;

    const pollForm = document.querySelector('.poll-form');
    if (!pollForm) return;

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
            channelId: channelId,
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
})();
