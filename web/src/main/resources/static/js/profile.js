// Wire the "Claim daily" button on the profile streak card to the
// /api/engagement/{guildId}/daily/claim REST endpoint. Server response
// drives the in-place UI update — no full page reload.
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        const card = document.querySelector('.profile-streak-card');
        if (!card) return;

        const guildId = card.getAttribute('data-guild-id');
        const button = card.querySelector('.profile-streak-claim');
        if (!button || !guildId) return;

        button.addEventListener('click', function () {
            if (button.disabled) return;
            button.disabled = true;
            const originalLabel = button.textContent;
            button.textContent = 'Claiming…';

            window.TobyApi.postJson('/api/engagement/' + guildId + '/daily/claim', {})
                .then(function (resp) {
                    if (!resp || !resp.status) {
                        button.disabled = false;
                        button.textContent = originalLabel;
                        return;
                    }
                    const nums = card.querySelectorAll('.profile-streak-num');
                    if (nums.length >= 2) {
                        nums[0].textContent = String(resp.currentStreak);
                        nums[1].textContent = String(resp.longestStreak);
                    }
                    button.textContent = 'Already claimed today';
                    card.setAttribute('data-claimed-today', 'true');
                })
                .catch(function () {
                    button.disabled = false;
                    button.textContent = originalLabel;
                });
        });
    });
})();
