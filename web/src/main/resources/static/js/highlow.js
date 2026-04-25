// Pure card-face label. Hoisted so the jest test can call it directly.
function highlowCardLabel(n) {
    switch (n) {
        case 1: return 'A';
        case 11: return 'J';
        case 12: return 'Q';
        case 13: return 'K';
        default: return String(n);
    }
}

// Pure-DOM render for a /play response. Hoisted out of the IIFE so the
// jest test in `highlow.test.js` can drive it without booting the page.
function renderHighlowResult(resultEl, body) {
    if (!resultEl) return;
    resultEl.hidden = false;
    resultEl.classList.remove('highlow-result-win', 'highlow-result-lose', 'highlow-result-jackpot');
    const dirLabel = body.direction === 'HIGHER' ? 'Higher' : 'Lower';
    const topUpPrefix = (typeof window !== 'undefined' && window.TobyTopUp)
        ? window.TobyTopUp.soldPrefixHtml(body.soldTobyCoins, body.newPrice)
        : '';
    if (body.win) {
        resultEl.classList.add('highlow-result-win');
        const winLine = '<strong>' + highlowCardLabel(body.next) + '</strong> ' +
            (body.next > body.anchor ? '>' : '<') + ' <strong>' + highlowCardLabel(body.anchor) +
            '</strong> &middot; you called ' + dirLabel + ' &middot; <strong>+' + body.net + ' credits</strong>';
        const withJackpot = (typeof window !== 'undefined' && window.TobyJackpot)
            ? window.TobyJackpot.renderWinHtml(resultEl, body, 'highlow-result-jackpot', winLine)
            : winLine;
        resultEl.innerHTML = topUpPrefix + withJackpot;
    } else {
        resultEl.classList.add('highlow-result-lose');
        const tie = body.next === body.anchor;
        resultEl.innerHTML = topUpPrefix + '<strong>' + highlowCardLabel(body.next) + '</strong> ' +
            (tie ? '=' : (body.next > body.anchor ? '>' : '<')) +
            ' <strong>' + highlowCardLabel(body.anchor) + '</strong> &middot; you called ' +
            dirLabel + ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>';
    }
}

(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const initialTobyCoins = Number(main.dataset.tobyCoins) || 0;
    const initialMarketPrice = Number(main.dataset.marketPrice) || 0;
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
    const dealTobyBtn = document.getElementById('highlow-deal-toby');
    const balanceEl = document.getElementById('highlow-balance');
    const resultEl = document.getElementById('highlow-result');
    const form = document.getElementById('highlow-bet');

    if (!form || !dealBtn || !stakeInput || !anchorFace || !nextFace) return;

    const topUp = (window.TobyTopUp && dealTobyBtn) ? window.TobyTopUp.init({
        form: form,
        stakeInput: stakeInput,
        primaryBtn: dealBtn,
        tobyBtn: dealTobyBtn,
        balanceEl: balanceEl,
        tobyCoins: initialTobyCoins,
        marketPrice: initialMarketPrice,
        onSubmit: function (autoTopUp) { runDeal(autoTopUp); },
    }) : null;

    const NEXT_DEAL_MS = 900;
    const SHUFFLE_INTERVAL_MS = 70;

    let dealing = false;

    function selectedDirection() {
        const checked = form.querySelector('input[name="direction"]:checked');
        return checked ? checked.value : null;
    }

    const cardLabel = highlowCardLabel;

    function startNextShuffle() {
        dealing = true;
        nextCard.classList.add('shuffling');
        return setInterval(function () {
            nextFace.textContent = cardLabel(1 + Math.floor(Math.random() * 13));
        }, SHUFFLE_INTERVAL_MS);
    }

    function stopNextShuffle(intervalId, value) {
        clearInterval(intervalId);
        dealing = false;
        nextCard.classList.remove('shuffling');
        if (typeof value === 'number') {
            nextFace.textContent = cardLabel(value);
            nextCard.dataset.value = String(value);
        } else {
            nextFace.textContent = '?';
            delete nextCard.dataset.value;
        }
    }

    // After a settled round, surface the next round's anchor in the
    // anchor slot and reset the next-card placeholder so the player can
    // see the new draw without refreshing the page.
    function rollAnchorTo(value) {
        if (typeof value !== 'number') return;
        anchorFace.textContent = cardLabel(value);
        anchorCard.dataset.value = String(value);
        nextFace.textContent = '?';
        delete nextCard.dataset.value;
    }

    function applyBalance(newBalance) {
        if (typeof newBalance !== 'number') return;
        if (balanceEl) balanceEl.textContent = newBalance;
    }

    function applyTobyDelta(body) {
        if (!topUp) return;
        if (typeof body.soldTobyCoins === 'number') {
            const remaining = Math.max(0, initialTobyCoins - body.soldTobyCoins);
            topUp.setTobyCoins(remaining);
        }
        if (typeof body.newPrice === 'number') topUp.setMarketPrice(body.newPrice);
    }

    function runDeal(autoTopUp) {
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
        if (dealTobyBtn) dealTobyBtn.disabled = true;
        const intervalId = startNextShuffle();
        const requestStart = Date.now();

        if (!postJson) {
            stopNextShuffle(intervalId);
            dealBtn.disabled = false;
            if (dealTobyBtn) dealTobyBtn.disabled = false;
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        postJson('/casino/' + guildId + '/highlow/play', {
            direction: direction, stake: stake, autoTopUp: !!autoTopUp,
        })
            .then(function (body) {
                const elapsed = Date.now() - requestStart;
                const remaining = Math.max(0, NEXT_DEAL_MS - elapsed);
                setTimeout(function () {
                    if (body && body.ok) {
                        stopNextShuffle(intervalId, body.next);
                        renderHighlowResult(resultEl, body);
                        applyBalance(body.newBalance);
                        applyTobyDelta(body);
                        if (typeof body.nextAnchor === 'number') {
                            setTimeout(function () { rollAnchorTo(body.nextAnchor); }, 1200);
                        }
                    } else {
                        stopNextShuffle(intervalId);
                        if (resultEl) resultEl.hidden = true;
                        toast((body && body.error) || 'Deal failed.', 'error');
                    }
                    dealBtn.disabled = false;
                    if (dealTobyBtn) dealTobyBtn.disabled = false;
                }, remaining);
            })
            .catch(function () {
                stopNextShuffle(intervalId);
                dealBtn.disabled = false;
                if (dealTobyBtn) dealTobyBtn.disabled = false;
                if (resultEl) resultEl.hidden = true;
                toast('Network error.', 'error');
            });
    }

    if (!topUp) {
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            runDeal(false);
        });
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderHighlowResult, highlowCardLabel };
}
