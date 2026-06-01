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

    // Surface the reward the claim actually granted — xpGranted /
    // creditsGranted / newBest come back on the response and were
    // previously thrown away, leaving the user with no feedback.
    function showReward(card, resp) {
        const el = card.querySelector('.profile-streak-claimed-reward');
        if (!el) return;
        const parts = [];
        if (resp.xpGranted > 0) parts.push('+' + resp.xpGranted + ' XP');
        if (resp.creditsGranted > 0) parts.push('+' + resp.creditsGranted + ' credits');
        let text = parts.length ? '🎉 Claimed ' + parts.join(' · ') : '🎉 Claimed!';
        if (resp.newBest) text += ' · 🔥 New personal best!';
        el.textContent = text;
        el.hidden = false;
        el.classList.add('is-shown');
    }

    function bumpTotal(card) {
        const total = card.querySelector('.profile-streak-total');
        if (!total) return;
        const n = parseInt(total.textContent, 10);
        if (!isNaN(n)) total.textContent = String(n + 1);
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
                    const alreadyClaimed = resp.status === 'already_claimed';
                    const nums = card.querySelectorAll('.profile-streak-num');
                    if (nums.length >= 2) {
                        nums[0].textContent = String(resp.currentStreak);
                        nums[1].textContent = String(resp.longestStreak);
                    }
                    updateWeek(card, resp.currentStreak);
                    card.classList.add('is-lit');
                    card.classList.remove('is-lapsed');
                    card.setAttribute('data-streak', String(resp.currentStreak));
                    card.setAttribute('data-claimed-today', 'true');
                    button.textContent = '✓ Claimed today';

                    // The "at risk", "lapsed" and next-reward preview lines
                    // describe the pre-claim state — drop them now it's claimed.
                    card.querySelectorAll('.profile-streak-alert, .profile-streak-preview')
                        .forEach(function (n) { n.remove(); });

                    if (!alreadyClaimed) {
                        bumpTotal(card);
                        showReward(card, resp);
                    }
                })
                .catch(function () {
                    button.disabled = false;
                    button.textContent = originalLabel;
                });
        });
    });
})();
