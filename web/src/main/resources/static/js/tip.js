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

        const recipient = document.getElementById('tip-recipient').value;
        const amount = parseInt(document.getElementById('tip-amount').value, 10);
        const note = (document.getElementById('tip-note').value || '').trim() || null;

        if (!recipient) {
            toast('Pick someone from the list.', 'error');
            return;
        }

        if (!amount) {
            toast('Amount is required.', 'error');
            return;
        }

        window.TobyApi.postJson('/tip/' + guildId, {
            recipientDiscordId: recipient,
            amount: amount,
            note: note
        }).then(function (resp) {
            if (!resp || !resp.ok) {
                toast((resp && resp.error) || 'Tip failed.', 'error');
                return;
            }

            window.TobyBalance.update(balanceEl, resp.senderNewBalance);
            if (sentEl) sentEl.textContent = String(resp.sentTodayAfter);

            if (headroomEl) {
                const cap = resp.dailyCap || dailyCap;
                headroomEl.textContent = String(Math.max(0, cap - resp.sentTodayAfter));
            }

            const noteSuffix = resp.note ? ' (' + resp.note + ')' : '';

            toast(
                'Sent ' + resp.amount + ' credits' + noteSuffix + '.',
                'success'
            );

        }).catch(function () {
            toast('Network error sending tip.', 'error');
        });
    });
})();