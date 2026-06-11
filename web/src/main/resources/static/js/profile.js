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

    // Reflect the claim's XP award on the Level card so level, progress bar,
    // and totals stay accurate without a reload. The claim response carries
    // the authoritative post-claim figures computed server-side.
    function updateLevelCard(resp) {
        const levelCard = document.querySelector('.level-card');
        if (!levelCard) return;
        if (typeof resp.xpForNextLevel !== 'number' || resp.xpForNextLevel <= 0) return;

        const badge = levelCard.querySelector('.level-badge-value');
        if (badge) badge.textContent = String(resp.level);

        const current = levelCard.querySelector('.level-current');
        if (current) current.textContent = resp.xpIntoLevel + ' XP';

        const next = levelCard.querySelector('.level-next');
        if (next) next.textContent = resp.xpForNextLevel + ' XP';

        const progress = levelCard.querySelector('.level-progress');
        if (progress) progress.setAttribute('aria-valuenow', String(resp.xpProgressPercent));

        const bar = levelCard.querySelector('.level-progress-bar');
        if (bar) bar.style.width = resp.xpProgressPercent + '%';

        const totalXp = levelCard.querySelector('.level-total-xp');
        if (totalXp) totalXp.textContent = String(resp.totalXp);

        // Keep the tier styling (bronze/silver/gold/diamond) in sync with the
        // new level — mirrors the Thymeleaf threshold logic.
        const tier = resp.level >= 50 ? 'diamond'
            : resp.level >= 25 ? 'gold'
            : resp.level >= 10 ? 'silver' : 'bronze';
        levelCard.classList.remove(
            'level-tier-bronze', 'level-tier-silver', 'level-tier-gold', 'level-tier-diamond'
        );
        levelCard.classList.add('level-tier-' + tier);
    }

    // Reflect granted credits on the Economy card's balance line.
    function updateBalance(resp) {
        if (typeof resp.balance !== 'number') return;
        const el = document.querySelector('.profile-balance');
        if (!el) return;
        el.textContent = resp.balance + ' credits';
    }

    // "N days to your next 7-day milestone" — mirrors the Thymeleaf
    // calculation so the line stays correct after an in-place claim.
    function updateMilestone(card, currentStreak) {
        const el = card.querySelector('.profile-streak-milestone');
        if (!el) return;
        if (currentStreak <= 0) { el.hidden = true; return; }
        const cycleDay = ((currentStreak - 1) % 7) + 1;
        const left = 7 - cycleDay;
        el.textContent = left === 0
            ? '🎉 7-day milestone reached!'
            : left + (left === 1 ? ' day' : ' days') + ' to your next milestone';
        el.hidden = false;
    }

    // One-shot flame flourish on a new personal best.
    function celebrate(card) {
        const flame = card.querySelector('.profile-streak-flame');
        if (!flame) return;
        flame.classList.remove('is-celebrating');
        // Force reflow so the animation re-triggers if best is beaten again.
        void flame.offsetWidth;
        flame.classList.add('is-celebrating');
    }

    // Live "Resets in 6h 12m" countdown for an at-risk streak — the streak
    // breaks at the next UTC midnight (matching DefaultLoginStreakService),
    // so turn the warning into real urgency. Returns the interval id.
    function startCountdown(card) {
        const el = card.querySelector('.profile-streak-countdown');
        if (!el) return null;
        function tick() {
            const now = new Date();
            const nextMidnight = Date.UTC(
                now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 0, 0
            );
            const ms = nextMidnight - now.getTime();
            if (ms <= 0) { el.textContent = 'Resets at midnight UTC'; return; }
            const mins = Math.floor(ms / 60000);
            const h = Math.floor(mins / 60);
            const m = mins % 60;
            el.textContent = h > 0
                ? 'Resets in ' + h + 'h ' + m + 'm'
                : (m > 0 ? 'Resets in ' + m + 'm' : 'Resets in <1m');
            el.hidden = false;
        }
        tick();
        return window.setInterval(tick, 30000);
    }

    document.addEventListener('DOMContentLoaded', function () {
        const card = document.querySelector('.profile-streak-card');
        if (!card) return;

        let countdownId = null;
        if (card.getAttribute('data-status') === 'AT_RISK') {
            countdownId = startCountdown(card);
        }

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
                    updateMilestone(card, resp.currentStreak);
                    card.classList.add('is-lit');
                    card.classList.remove('is-lapsed');
                    card.setAttribute('data-streak', String(resp.currentStreak));
                    card.setAttribute('data-claimed-today', 'true');
                    card.setAttribute('data-status', 'CLAIMED');
                    button.textContent = '✓ Claimed today';

                    // The "at risk", "lapsed" and next-reward preview lines
                    // describe the pre-claim state — drop them now it's claimed.
                    // The reset countdown goes with them.
                    if (countdownId) { window.clearInterval(countdownId); countdownId = null; }
                    card.querySelectorAll('.profile-streak-alert, .profile-streak-preview')
                        .forEach(function (n) { n.remove(); });

                    // The claim mutates XP and credits server-side; mirror
                    // those onto the Level and Economy cards so the page
                    // doesn't need a manual refresh to reflect the reward.
                    updateLevelCard(resp);
                    updateBalance(resp);

                    if (!alreadyClaimed) {
                        bumpTotal(card);
                        showReward(card, resp);
                        if (resp.newBest) celebrate(card);
                    }
                })
                .catch(function () {
                    button.disabled = false;
                    button.textContent = originalLabel;
                });
        });
    });
})();
