// Wire the "Claim daily" button on the profile streak card to the
// /api/engagement/{guildId}/daily/claim REST endpoint. Server response
// drives the in-place UI update — no full page reload.
(function () {
    'use strict';

    // Fill the 7-day cycle dots based on the current streak. The cycle
    // repeats every 7 days, so day 8 lights one dot again, etc. — mirrors
    // the Thymeleaf-rendered initial state.
    function updateWeek(card, currentStreak) {
        const days = card.querySelectorAll('.profile-streak-day');
        if (!days.length) return;
        const filled = currentStreak <= 0 ? 0 : ((currentStreak - 1) % 7) + 1;
        days.forEach(function (day, idx) {
            day.classList.toggle('is-filled', idx + 1 <= filled);
        });
    }

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
                    updateWeek(card, resp.currentStreak);
                    card.classList.toggle('is-lit', resp.currentStreak > 0);
                    card.setAttribute('data-streak', String(resp.currentStreak));
                    button.textContent = '✓ Claimed today';
                    card.setAttribute('data-claimed-today', 'true');
                })
                .catch(function () {
                    button.disabled = false;
                    button.textContent = originalLabel;
                });
        });
    });
})();
