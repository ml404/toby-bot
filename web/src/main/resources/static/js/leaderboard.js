(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;

    // Persist open/closed state per section per guild, so a user who prefers
    // the TobyCoin table collapsed keeps it that way on return visits.
    document.querySelectorAll('details.lb-collapse[data-section]').forEach((det) => {
        const section = det.dataset.section;
        const key = 'lb-collapse:' + guildId + ':' + section;

        try {
            const stored = window.localStorage.getItem(key);
            if (stored === 'closed') det.open = false;
            else if (stored === 'open') det.open = true;
        } catch (_) { /* localStorage unavailable — ignore */ }

        det.addEventListener('toggle', () => {
            try {
                window.localStorage.setItem(key, det.open ? 'open' : 'closed');
            } catch (_) { /* ignore */ }
        });
    });
})();
