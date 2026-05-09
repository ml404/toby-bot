// Casino tab: jackpot pool reset + refund-from-user remediation.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, postJson } = ctx;

    const jackpotPoolEl = document.getElementById('jackpot-pool');
    const jackpotResetBtn = document.getElementById('jackpot-reset');
    if (jackpotResetBtn) {
        jackpotResetBtn.addEventListener('click', () => {
            const current = Number(jackpotPoolEl?.textContent || '0');
            if (current === 0) {
                toast('Pool is already empty.', 'info');
                return;
            }
            if (!confirm('Reset the jackpot pool to 0? This drains ' + current + ' credits.')) return;
            jackpotResetBtn.disabled = true;
            postJson('/moderation/' + guildId + '/jackpot/reset', {}).then(r => {
                jackpotResetBtn.disabled = false;
                if (r && r.ok) {
                    if (jackpotPoolEl) jackpotPoolEl.textContent = String(r.newPool ?? 0);
                    toast('Jackpot reset. Drained ' + (r.drained ?? 0) + ' credits.', 'success');
                } else {
                    toast(r?.error || 'Could not reset jackpot.', 'error');
                }
            }).catch(() => { jackpotResetBtn.disabled = false; toast('Network error.', 'error'); });
        });
    }

    const jackpotRefundForm = document.querySelector('.jackpot-refund-form');
    if (!jackpotRefundForm) return;

    const sourceSelect = jackpotRefundForm.elements.sourceDiscordId;
    const balanceHint = jackpotRefundForm.querySelector('.jackpot-refund-balance');
    const balanceValue = jackpotRefundForm.querySelector('.jackpot-refund-balance-value');
    const updateBalanceHint = () => {
        const opt = sourceSelect.selectedOptions[0];
        const credit = opt && opt.dataset.credit;
        if (credit != null && opt.value) {
            balanceValue.textContent = credit;
            balanceHint.hidden = false;
        } else {
            balanceHint.hidden = true;
        }
    };
    sourceSelect.addEventListener('change', updateBalanceHint);
    jackpotRefundForm.addEventListener('submit', e => {
        e.preventDefault();
        const sourceDiscordId = sourceSelect.value;
        const amount = Number(jackpotRefundForm.elements.amount.value);
        if (!/^[0-9]+$/.test(sourceDiscordId) || !Number.isFinite(amount) || amount <= 0) {
            toast('Select a member and enter a positive amount.', 'error');
            return;
        }
        const selectedOpt = sourceSelect.selectedOptions[0];
        const memberLabel = selectedOpt ? (selectedOpt.textContent.split(' — ')[0] || sourceDiscordId) : sourceDiscordId;
        if (!confirm('Refund ' + amount + ' credits from ' + memberLabel + ' into the jackpot pool?')) return;
        const submitBtn = jackpotRefundForm.querySelector('button[type="submit"]');
        submitBtn.disabled = true;
        postJson('/moderation/' + guildId + '/jackpot/refund', {
            sourceDiscordId: sourceDiscordId,
            amount: amount
        }).then(r => {
            submitBtn.disabled = false;
            if (r && r.ok) {
                if (jackpotPoolEl) jackpotPoolEl.textContent = String(r.newPool ?? 0);
                if (selectedOpt && r.newSourceBalance != null) {
                    selectedOpt.dataset.credit = String(r.newSourceBalance);
                    const name = selectedOpt.textContent.split(' — ')[0];
                    selectedOpt.textContent = name + ' — ' + r.newSourceBalance + ' credits';
                }
                toast(
                    'Refunded ' + (r.drained ?? 0) + ' credits. New pool: ' + (r.newPool ?? 0) + '.',
                    'success'
                );
                jackpotRefundForm.reset();
                updateBalanceHint();
            } else {
                toast(r?.error || 'Could not refund to jackpot.', 'error');
            }
        }).catch(() => { submitBtn.disabled = false; toast('Network error.', 'error'); });
    });
})();
