(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;

    const postJson = window.TobyApi.postJson;
    const deleteJson = (window.TobyApi && window.TobyApi.deleteJson) || function (url) {
        return fetch(url, { method: 'DELETE', headers: { 'Accept': 'application/json' } })
            .then(r => r.json().catch(() => ({ ok: r.ok })));
    };

    document.querySelectorAll('.title-buy').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const titleId = row.dataset.titleId;
            btn.disabled = true;
            postJson('/titles/' + guildId + '/' + titleId + '/buy', {})
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

    document.querySelectorAll('.title-buy-toby').forEach(btn => {
        btn.addEventListener('click', () => {
            const row = btn.closest('tr');
            const titleId = row.dataset.titleId;
            btn.disabled = true;
            postJson('/titles/' + guildId + '/' + titleId + '/buy-with-toby', {})
                .then(r => {
                    btn.disabled = false;
                    if (r && r.ok) {
                        const sold = r.soldTobyCoins || 0;
                        toast(
                            sold > 0 ? 'Sold ' + sold + ' TOBY to buy the title.' : 'Title purchased.',
                            'success'
                        );
                        setTimeout(() => window.location.reload(), 600);
                    } else {
                        toast(r?.error || 'Could not buy with TOBY.', 'error');
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
            postJson('/titles/' + guildId + '/' + titleId + '/equip', {})
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
            deleteJson('/titles/' + guildId + '/equipped')
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
})();
