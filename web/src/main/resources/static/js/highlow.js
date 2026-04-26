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

// Format a payout multiplier for the UI (e.g. 1.50× ). Returns an
// empty string for non-finite or non-positive values so a missing
// multiplier just collapses the label rather than rendering "0.00×".
function highlowFormatMultiplier(m) {
    const num = Number(m);
    if (!Number.isFinite(num) || num <= 0) return '';
    return num.toFixed(2) + '×';
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
        const multSuffix = highlowFormatMultiplier(body.multiplier);
        const winLine = '<strong>' + highlowCardLabel(body.next) + '</strong> ' +
            (body.next > body.anchor ? '>' : '<') + ' <strong>' + highlowCardLabel(body.anchor) +
            '</strong> &middot; you called ' + dirLabel +
            (multSuffix ? ' (' + multSuffix + ')' : '') +
            ' &middot; <strong>+' + body.net + ' credits</strong>';
        const withJackpot = (typeof window !== 'undefined' && window.TobyJackpot)
            ? window.TobyJackpot.renderWinHtml(resultEl, body, 'highlow-result-jackpot', winLine)
            : winLine;
        resultEl.innerHTML = topUpPrefix + withJackpot;
    } else {
        resultEl.classList.add('highlow-result-lose');
        const tie = body.next === body.anchor;
        const tributeSuffix = (typeof window !== 'undefined' && window.TobyJackpot)
            ? window.TobyJackpot.lossTributeSuffix(body)
            : '';
        resultEl.innerHTML = topUpPrefix + '<strong>' + highlowCardLabel(body.next) + '</strong> ' +
            (tie ? '=' : (body.next > body.anchor ? '>' : '<')) +
            ' <strong>' + highlowCardLabel(body.anchor) + '</strong> &middot; you called ' +
            dirLabel + ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>' +
            tributeSuffix;
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
    const directionPicker = document.getElementById('highlow-direction-picker');
    const higherBtn = document.getElementById('highlow-call-higher');
    const lowerBtn = document.getElementById('highlow-call-lower');
    const higherMultEl = document.getElementById('highlow-call-higher-mult');
    const lowerMultEl = document.getElementById('highlow-call-lower-mult');

    if (!form || !dealBtn || !stakeInput || !anchorFace || !nextFace) return;
    if (!directionPicker || !higherBtn || !lowerBtn) return;

    function setMultiplierLabels(higher, lower) {
        if (higherMultEl) higherMultEl.textContent = highlowFormatMultiplier(higher);
        if (lowerMultEl) lowerMultEl.textContent = highlowFormatMultiplier(lower);
    }

    function clearMultiplierLabels() {
        setMultiplierLabels(null, null);
    }

    const topUp = (window.TobyTopUp && dealTobyBtn) ? window.TobyTopUp.init({
        form: form,
        stakeInput: stakeInput,
        primaryBtn: dealBtn,
        tobyBtn: dealTobyBtn,
        balanceEl: balanceEl,
        tobyCoins: initialTobyCoins,
        marketPrice: initialMarketPrice,
        onSubmit: function (autoTopUp) { runStart(autoTopUp); },
    }) : null;

    const NEXT_DEAL_MS = 900;
    const SHUFFLE_INTERVAL_MS = 70;
    const ROUND_RESET_MS = 1500;

    let dealing = false;
    // Tracks which lock variant the player picked so we can pass autoTopUp
    // through to /play if they hit the shortfall on the actual wager.
    let pendingAutoTopUp = false;

    const cardLabel = highlowCardLabel;

    function showLockMode() {
        directionPicker.hidden = true;
        higherBtn.disabled = false;
        lowerBtn.disabled = false;
        form.hidden = false;
        dealBtn.disabled = false;
        if (dealTobyBtn) dealTobyBtn.disabled = false;
        anchorFace.textContent = '?';
        if (anchorCard) delete anchorCard.dataset.value;
        nextFace.textContent = '?';
        if (nextCard) delete nextCard.dataset.value;
        clearMultiplierLabels();
    }

    function showCallMode(anchorValue, higherMult, lowerMult) {
        if (typeof anchorValue === 'number') {
            anchorFace.textContent = cardLabel(anchorValue);
            if (anchorCard) anchorCard.dataset.value = String(anchorValue);
        }
        nextFace.textContent = '?';
        if (nextCard) delete nextCard.dataset.value;
        form.hidden = true;
        directionPicker.hidden = false;
        higherBtn.disabled = false;
        lowerBtn.disabled = false;
        setMultiplierLabels(higherMult, lowerMult);
        if (resultEl) resultEl.hidden = true;
    }

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

    function runStart(autoTopUp) {
        if (dealing) return;

        const stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            toast('Enter a positive stake.', 'error');
            return;
        }

        if (!postJson) {
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        dealBtn.disabled = true;
        if (dealTobyBtn) dealTobyBtn.disabled = true;
        pendingAutoTopUp = !!autoTopUp;

        postJson('/casino/' + guildId + '/highlow/start', {
            stake: stake, autoTopUp: pendingAutoTopUp,
        })
            .then(function (body) {
                if (body && body.ok && typeof body.anchor === 'number') {
                    showCallMode(body.anchor, body.higherMultiplier, body.lowerMultiplier);
                } else {
                    dealBtn.disabled = false;
                    if (dealTobyBtn) dealTobyBtn.disabled = false;
                    toast((body && body.error) || 'Could not lock the round.', 'error');
                }
            })
            .catch(function () {
                dealBtn.disabled = false;
                if (dealTobyBtn) dealTobyBtn.disabled = false;
                toast('Network error.', 'error');
            });
    }

    function runPlay(direction) {
        if (dealing) return;
        if (!postJson) {
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        higherBtn.disabled = true;
        lowerBtn.disabled = true;
        const intervalId = startNextShuffle();
        const requestStart = Date.now();

        postJson('/casino/' + guildId + '/highlow/play', {
            direction: direction,
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
                        // Round consumed → reset to lock mode after the
                        // player has had a moment to read the result.
                        setTimeout(showLockMode, ROUND_RESET_MS);
                    } else {
                        stopNextShuffle(intervalId);
                        higherBtn.disabled = false;
                        lowerBtn.disabled = false;
                        toast((body && body.error) || 'Deal failed.', 'error');
                    }
                }, remaining);
            })
            .catch(function () {
                stopNextShuffle(intervalId);
                higherBtn.disabled = false;
                lowerBtn.disabled = false;
                toast('Network error.', 'error');
            });
    }

    if (!topUp) {
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            runStart(false);
        });
    }

    higherBtn.addEventListener('click', function () { runPlay('HIGHER'); });
    lowerBtn.addEventListener('click', function () { runPlay('LOWER'); });

    // If the server pre-rendered an active round (page refresh mid-round),
    // jump straight to call mode so the player can finish their bet.
    const preloadedAnchor = parseInt(main.dataset.activeAnchor || '', 10);
    if (Number.isFinite(preloadedAnchor) && preloadedAnchor > 0) {
        const preloadedHigher = parseFloat(main.dataset.higherMultiplier || '');
        const preloadedLower = parseFloat(main.dataset.lowerMultiplier || '');
        showCallMode(preloadedAnchor, preloadedHigher, preloadedLower);
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderHighlowResult, highlowCardLabel, highlowFormatMultiplier };
}
