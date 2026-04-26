// /tip — JSON POST to /tip/{guildId} via the shared CSRF-aware fetch
// wrapper. Updates balance + daily-tipped counters in place; surfaces
// service errors as toasts.
(function () {
    'use strict';

    const main = document.getElementById('main');
    if (!main) return;
    const guildId = main.dataset.guildId;
    if (!guildId) return;
    const dailyCap = parseInt(main.dataset.dailyCap, 10) || 0;

    const form = document.getElementById('tip-form');
    const balanceEl = document.getElementById('tip-balance');
    const sentEl = document.getElementById('tip-sent-today');
    const headroomEl = document.getElementById('tip-headroom');

    if (!form) return;

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        const recipientStr = (document.getElementById('tip-recipient').value || '').trim();
        const recipient = parseInt(recipientStr, 10);
        const amount = parseInt(document.getElementById('tip-amount').value, 10);
        const note = (document.getElementById('tip-note').value || '').trim() || null;
        if (!recipient || !amount) {
            window.TobyToasts && window.TobyToasts.error('Recipient and amount are required.');
            return;
        }
        window.TobyApi.postJson('/tip/' + guildId, {
            recipientDiscordId: recipient,
            amount: amount,
            note: note
        }).then(function (resp) {
            if (!resp || !resp.ok) {
                window.TobyToasts && window.TobyToasts.error((resp && resp.error) || 'Tip failed.');
                return;
            }
            if (balanceEl) balanceEl.textContent = String(resp.senderNewBalance);
            if (sentEl) sentEl.textContent = String(resp.sentTodayAfter);
            if (headroomEl) {
                const cap = resp.dailyCap || dailyCap;
                headroomEl.textContent = String(Math.max(0, cap - resp.sentTodayAfter));
            }
            const noteSuffix = resp.note ? ' (' + resp.note + ')' : '';
            window.TobyToasts && window.TobyToasts.success(
                'Sent ' + resp.amount + ' credits' + noteSuffix + '.'
            );
        }).catch(function () {
            window.TobyToasts && window.TobyToasts.error('Network error sending tip.');
        });
    });
})();
