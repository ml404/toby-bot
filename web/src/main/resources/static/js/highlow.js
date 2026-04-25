(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const postJson = window.TobyApi && window.TobyApi.postJson;

    function toast(msg, type) {
        if (window.TobyToast && typeof window.TobyToast.show === 'function') {
            window.TobyToast.show(msg, { type: type || 'info' });
        } else {
            console.log('[' + (type || 'info') + '] ' + msg);
        }
    }

    const anchorFace = document.getElementById('highlow-anchor-face');
    const nextFace = document.getElementById('highlow-next-face');
    const anchorCard = document.getElementById('highlow-anchor');
    const nextCard = document.getElementById('highlow-next');
    const stakeInput = document.getElementById('highlow-stake');
    const dealBtn = document.getElementById('highlow-deal');
    const balanceEl = document.getElementById('highlow-balance');
    const resultEl = document.getElementById('highlow-result');
    const form = document.getElementById('highlow-bet');

    if (!form || !dealBtn || !stakeInput || !anchorFace || !nextFace) return;

    const DEAL_DURATION_MS = 1000;
    const SHUFFLE_INTERVAL_MS = 70;

    let dealing = false;

    function selectedDirection() {
        const checked = form.querySelector('input[name="direction"]:checked');
        return checked ? checked.value : null;
    }

    function cardLabel(n) {
        switch (n) {
            case 1: return 'A';
            case 11: return 'J';
            case 12: return 'Q';
            case 13: return 'K';
            default: return String(n);
        }
    }

    function startShuffle() {
        dealing = true;
        anchorCard.classList.add('shuffling');
        nextCard.classList.add('shuffling');
        return setInterval(function () {
            anchorFace.textContent = cardLabel(1 + Math.floor(Math.random() * 13));
            nextFace.textContent = cardLabel(1 + Math.floor(Math.random() * 13));
        }, SHUFFLE_INTERVAL_MS);
    }

    function stopShuffle(intervalId, body) {
        clearInterval(intervalId);
        dealing = false;
        anchorCard.classList.remove('shuffling');
        nextCard.classList.remove('shuffling');
        if (body && typeof body.anchor === 'number' && typeof body.next === 'number') {
            anchorFace.textContent = cardLabel(body.anchor);
            nextFace.textContent = cardLabel(body.next);
            anchorCard.dataset.value = String(body.anchor);
            nextCard.dataset.value = String(body.next);
        } else {
            anchorFace.textContent = '?';
            nextFace.textContent = '?';
        }
    }

    function showResult(body) {
        if (!resultEl) return;
        resultEl.hidden = false;
        resultEl.classList.remove('highlow-result-win', 'highlow-result-lose');
        const dirLabel = body.direction === 'HIGHER' ? 'Higher' : 'Lower';
        if (body.win) {
            resultEl.classList.add('highlow-result-win');
            resultEl.innerHTML = '<strong>' + cardLabel(body.next) + '</strong> ' +
                (body.next > body.anchor ? '>' : '<') + ' <strong>' + cardLabel(body.anchor) +
                '</strong> &middot; you called ' + dirLabel + ' &middot; <strong>+' + body.net + ' credits</strong>';
        } else {
            resultEl.classList.add('highlow-result-lose');
            const tie = body.next === body.anchor;
            resultEl.innerHTML = '<strong>' + cardLabel(body.next) + '</strong> ' +
                (tie ? '=' : (body.next > body.anchor ? '>' : '<')) +
                ' <strong>' + cardLabel(body.anchor) + '</strong> &middot; you called ' +
                dirLabel + ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>';
        }
    }

    function applyBalance(newBalance) {
        if (typeof newBalance !== 'number') return;
        if (balanceEl) balanceEl.textContent = newBalance;
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        if (dealing) return;

        const direction = selectedDirection();
        if (!direction) {
            toast('Pick a direction first.', 'error');
            return;
        }
        const stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            toast('Enter a positive stake.', 'error');
            return;
        }

        dealBtn.disabled = true;
        const intervalId = startShuffle();
        const requestStart = Date.now();

        if (!postJson) {
            stopShuffle(intervalId);
            dealBtn.disabled = false;
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        postJson('/casino/' + guildId + '/highlow/play', { direction: direction, stake: stake })
            .then(function (body) {
                const elapsed = Date.now() - requestStart;
                const remaining = Math.max(0, DEAL_DURATION_MS - elapsed);
                setTimeout(function () {
                    stopShuffle(intervalId, body);
                    dealBtn.disabled = false;
                    if (body && body.ok) {
                        showResult(body);
                        applyBalance(body.newBalance);
                    } else {
                        if (resultEl) resultEl.hidden = true;
                        toast((body && body.error) || 'Deal failed.', 'error');
                    }
                }, remaining);
            })
            .catch(function () {
                stopShuffle(intervalId);
                dealBtn.disabled = false;
                if (resultEl) resultEl.hidden = true;
                toast('Network error.', 'error');
            });
    });
})();
