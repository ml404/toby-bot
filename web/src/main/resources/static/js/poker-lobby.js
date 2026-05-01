// /poker lobby — list of tables in this guild + new-table form. Polls
// the same /poker/{guildId}/{tableId}/state-style data via the lobby
// endpoint isn't strictly needed since tables update on action; we just
// re-render the list every 5s by re-fetching the lobby HTML's data via
// a JSON projection. For v1 we trigger a full reload after create/join.
(function () {
    'use strict';

    const main = document.getElementById('main');
    if (!main) return;
    const guildId = main.dataset.guildId;
    if (!guildId) return;

    function showToast(level, msg) {
        if (window.TobyToasts && window.TobyToasts[level]) window.TobyToasts[level](msg);
    }

    const createForm = document.getElementById('poker-create');
    if (createForm) {
        createForm.addEventListener('submit', function (e) {
            e.preventDefault();
            const buyIn = parseInt(document.getElementById('poker-create-buyin').value, 10);
            if (!buyIn) {
                showToast('error', 'Buy-in is required.');
                return;
            }
            const freeCheckbox = document.getElementById('poker-create-free');
            const free = !!(freeCheckbox && freeCheckbox.checked);
            window.TobyApi.postJson('/poker/' + guildId + '/create', { buyIn: buyIn, free: free })
                .then(function (resp) {
                    if (!resp || !resp.ok) {
                        showToast('error', (resp && resp.error) || 'Could not create table.');
                        return;
                    }
                    showToast('success', 'Table #' + resp.tableId + ' created.');
                    window.location.href = '/poker/' + guildId + '/' + resp.tableId;
                })
                .catch(function () { showToast('error', 'Network error.'); });
        });
    }

    // Auto-refresh table list so newly-created tables show up without a
    // manual reload. Cheap: just reload the page on a long interval.
    setTimeout(function reload() {
        window.location.reload();
    }, 30000);
})();
