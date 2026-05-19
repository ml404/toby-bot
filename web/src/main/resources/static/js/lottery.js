/* global TobyApi, TobyBalance, toast */
/**
 * Web lottery page logic.
 *
 *  - Number picker (Pick 5 of 1-49 — read from data-pick-count /
 *    data-number-max). Click a cell to toggle; max N picks; visual
 *    feedback via .is-picked.
 *  - Quick Pick + Clear helpers.
 *  - Submit ticket via POST /casino/{guildId}/lottery/match/buy with
 *    `{ picks: [...] }`. On success, refresh the balance and reload
 *    the page so server-side rendering shows "Your ticket" panel.
 *  - Live countdown to closes_at.
 *  - Featured weighted-event card: separate buy form posting to
 *    /casino/{guildId}/lottery/weighted/buy with `{ count: N }`.
 *
 * Reuses the casino-balance / api / toasts shared modules; doesn't go
 * through the casino-game scaffold because the lottery's POST→reload
 * shape doesn't match the spin-animate-settle scaffold (the server
 * side tells us "you bought" — actual results land on the next draw,
 * surfaced via the GET-page render).
 */
(function () {
    'use strict';

    const main = document.getElementById('main');
    if (!main) return;
    const guildId = main.dataset.guildId;
    const pickCount = parseInt(main.dataset.pickCount, 10) || 5;
    const numberMax = parseInt(main.dataset.numberMax, 10) || 49;

    initDailyPicker();
    initWeightedBet();
    initCountdowns();

    function initDailyPicker() {
        const grid = document.getElementById('lottery-cells');
        const form = document.getElementById('lottery-bet');
        if (!grid || !form) return;
        const counter = document.getElementById('lottery-pickcount-value');
        const buyBtn = document.getElementById('lottery-buy');
        const balanceEl = document.getElementById('lottery-balance');
        const resultEl = document.getElementById('lottery-result');
        const quickPickBtn = document.getElementById('lottery-quickpick');
        const clearBtn = document.getElementById('lottery-clear');

        const cells = Array.from(grid.querySelectorAll('.lottery-cell'));

        function picked() {
            return cells.filter(c => c.classList.contains('is-picked'));
        }

        function setCount() {
            if (counter) counter.textContent = picked().length;
        }

        function togglePick(cell) {
            if (cell.classList.contains('is-picked')) {
                cell.classList.remove('is-picked');
                cell.setAttribute('aria-pressed', 'false');
            } else {
                if (picked().length >= pickCount) {
                    if (typeof toast === 'function') {
                        toast(`You can only pick ${pickCount} numbers.`, 'error');
                    }
                    return;
                }
                cell.classList.add('is-picked');
                cell.setAttribute('aria-pressed', 'true');
            }
            setCount();
        }

        cells.forEach(cell => {
            cell.addEventListener('click', () => togglePick(cell));
        });

        if (quickPickBtn) {
            quickPickBtn.addEventListener('click', () => {
                const need = pickCount - picked().length;
                if (need <= 0) return;
                const candidates = cells.filter(c => !c.classList.contains('is-picked'));
                // Fisher-Yates partial shuffle to pick `need` distinct cells.
                for (let i = 0; i < need; i++) {
                    const j = i + Math.floor(Math.random() * (candidates.length - i));
                    [candidates[i], candidates[j]] = [candidates[j], candidates[i]];
                    candidates[i].classList.add('is-picked');
                    candidates[i].setAttribute('aria-pressed', 'true');
                }
                setCount();
            });
        }

        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                cells.forEach(c => {
                    c.classList.remove('is-picked');
                    c.setAttribute('aria-pressed', 'false');
                });
                setCount();
            });
        }

        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const selected = picked().map(c => parseInt(c.dataset.value, 10));
            if (selected.length !== pickCount) {
                if (typeof toast === 'function') {
                    toast(`Pick exactly ${pickCount} numbers.`, 'error');
                }
                return;
            }
            buyBtn.disabled = true;
            try {
                const body = await TobyApi.postJson(
                    `/casino/${guildId}/lottery/match/buy`,
                    { picks: selected }
                );
                if (!body.ok) {
                    if (typeof toast === 'function') {
                        toast(body.error || 'Ticket purchase failed.', 'error');
                    }
                    if (resultEl) {
                        resultEl.hidden = false;
                        resultEl.textContent = body.error || 'Ticket purchase failed.';
                        resultEl.classList.add('lottery-result-lose');
                    }
                    return;
                }
                if (balanceEl) TobyBalance.update(balanceEl, body.newBalance);
                if (resultEl) {
                    resultEl.hidden = false;
                    resultEl.classList.remove('lottery-result-lose');
                    resultEl.classList.add('lottery-result-win');
                    resultEl.innerHTML =
                        `Bought ticket • picked <strong>${selected.join(', ')}</strong>. ` +
                        `<span class="casino-loss-tribute">+${body.jackpotInflow} to jackpot</span>`;
                }
                // Reload so the server-rendered "Your ticket" panel + live
                // pool numbers appear without us having to maintain a JS
                // shadow copy of the snapshot.
                setTimeout(() => window.location.reload(), 1200);
            } catch (err) {
                if (typeof toast === 'function') {
                    toast('Network error. Try again.', 'error');
                }
            } finally {
                buyBtn.disabled = false;
            }
        });

        setCount();
    }

    function initWeightedBet() {
        const form = document.getElementById('lottery-weighted-bet');
        if (!form) return;
        const countInput = document.getElementById('weighted-count');
        const buyBtn = document.getElementById('weighted-buy');
        const poolEl = document.getElementById('weighted-pool');
        const myEl = document.getElementById('weighted-my-tickets');
        const balanceEl = document.getElementById('lottery-balance');

        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const count = parseInt(countInput.value, 10) || 0;
            if (count <= 0) return;
            buyBtn.disabled = true;
            try {
                const body = await TobyApi.postJson(
                    `/casino/${guildId}/lottery/weighted/buy`,
                    { count: count }
                );
                if (!body.ok) {
                    if (typeof toast === 'function') {
                        toast(body.error || 'Buy failed.', 'error');
                    }
                    return;
                }
                if (poolEl) poolEl.textContent = body.newPool;
                // "Your tickets" now renders "X paid + Y bonus" when
                // the buyer has any bulk-bonus tickets — keeps parity
                // with the moderation page and the Discord status copy.
                if (myEl) {
                    if (body.totalBonusTickets && body.totalBonusTickets > 0) {
                        myEl.textContent = `${body.ticketCount} paid + ${body.totalBonusTickets} bonus`;
                    } else {
                        myEl.textContent = body.ticketCount;
                    }
                }
                if (balanceEl) TobyBalance.update(balanceEl, body.newBalance);
                if (typeof toast === 'function') {
                    // Base success copy + optional bulk-bonus and
                    // milestone callouts. Building the message in
                    // pieces so a regression on the response shape
                    // (missing field, zero bonus) silently degrades
                    // to the base copy rather than printing
                    // "undefined" into the toast.
                    let msg = `Bought ${count} tickets — total ${body.ticketCount} held.`;
                    if (body.bonusTicketsGranted && body.bonusTicketsGranted > 0) {
                        msg += ` 🎁 +${body.bonusTicketsGranted} bonus from bulk-buy!`;
                    }
                    toast(msg, 'success');
                    if (Array.isArray(body.milestoneBonuses)) {
                        body.milestoneBonuses.forEach(m => {
                            toast(
                                `🚀 Milestone @${m.threshold} tickets sold → +${m.creditsAdded} credits to the pool!`,
                                'success'
                            );
                        });
                    }
                }
            } catch (err) {
                if (typeof toast === 'function') {
                    toast('Network error. Try again.', 'error');
                }
            } finally {
                buyBtn.disabled = false;
            }
        });
    }

    function initCountdowns() {
        const targets = document.querySelectorAll('.lottery-countdown');
        if (!targets.length) return;
        function tick() {
            const now = Date.now();
            targets.forEach(el => {
                const target = parseInt(el.dataset.closesAt, 10);
                if (!target) return;
                const diff = Math.max(0, target - now);
                const valueEl = el.querySelector('.lottery-countdown-value');
                if (!valueEl) return;
                if (diff <= 0) {
                    valueEl.textContent = 'closing…';
                    return;
                }
                const totalSeconds = Math.floor(diff / 1000);
                const h = Math.floor(totalSeconds / 3600);
                const m = Math.floor((totalSeconds % 3600) / 60);
                const s = totalSeconds % 60;
                valueEl.textContent =
                    String(h).padStart(2, '0') + ':' +
                    String(m).padStart(2, '0') + ':' +
                    String(s).padStart(2, '0');
            });
        }
        tick();
        setInterval(tick, 1000);
    }
}());
