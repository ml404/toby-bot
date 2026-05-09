// Lottery tab: force-draw button. Generic config-row save and
// channel-create handlers live in moderationCommon.js.
(function () {
    'use strict';

    const ctx = window.ModerationCommon;
    if (!ctx) return;
    const { guildId, postJson } = ctx;

    const lotteryForceDrawBtn = document.getElementById('lottery-force-draw');
    if (!lotteryForceDrawBtn) return;

    lotteryForceDrawBtn.addEventListener('click', () => {
        if (!confirm(
            'Force the daily lottery cycle to run now? This closes any open draw ' +
            'and pays prizes by match tier, then opens a fresh draw seeded from the jackpot.'
        )) return;
        lotteryForceDrawBtn.disabled = true;
        postJson('/moderation/' + guildId + '/lottery/draw', {}).then(r => {
            lotteryForceDrawBtn.disabled = false;
            if (r && r.ok) {
                let msg = 'Lottery cycle ran.';
                if (r.drewPrior) {
                    if (r.priorBelowMinBuyers) {
                        msg += ' Prior draw cancelled — only ' + (r.priorBuyersHave ?? 0) +
                            ' distinct buyer(s), need ' + (r.priorBuyersNeed ?? 0) +
                            '. Refunded buyer(s); seed returned to jackpot.';
                    } else {
                        msg += ' Prior draw paid ' + (r.priorTotalPaid ?? 0) +
                            ' credits (' + (r.priorRolledBack ?? 0) + ' rolled back to jackpot).';
                    }
                }
                if (r.openedNew) {
                    msg += ' New draw seeded with ' + (r.newSeeded ?? 0) + ' credits.';
                }
                toast(msg, 'success');
            } else {
                toast(r?.error || 'Could not run the lottery cycle.', 'error');
            }
        }).catch(() => {
            lotteryForceDrawBtn.disabled = false;
            toast('Network error.', 'error');
        });
    });
})();
