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
    if (typeof window === 'undefined' || !window.TobyCasinoResult) return;
    const dirLabel = body.direction === 'HIGHER' ? 'Higher' : 'Lower';
    const tie = body.next === body.anchor;
    const multSuffix = highlowFormatMultiplier(body.multiplier);
    window.TobyCasinoResult.render({
        resultEl: resultEl,
        body: body,
        classPrefix: 'highlow',
        winLineHtml: '<strong>' + highlowCardLabel(body.next) + '</strong> ' +
            (body.next > body.anchor ? '>' : '<') + ' <strong>' + highlowCardLabel(body.anchor) +
            '</strong> &middot; you called ' + dirLabel +
            (multSuffix ? ' (' + multSuffix + ')' : '') +
            ' &middot; <strong>+' + body.net + ' credits</strong>',
        loseLineHtml: '<strong>' + highlowCardLabel(body.next) + '</strong> ' +
            (tie ? '=' : (body.next > body.anchor ? '>' : '<')) +
            ' <strong>' + highlowCardLabel(body.anchor) + '</strong> &middot; you called ' +
            dirLabel + ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>',
    });
}

(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const postJson = window.TobyApi && window.TobyApi.postJson;

    const anchorFace = document.getElementById('highlow-anchor-face');
    const nextFace = document.getElementById('highlow-next-face');
    const anchorCard = document.getElementById('highlow-anchor');
    const nextCard = document.getElementById('highlow-next');
    const tableEl = document.querySelector('.highlow-table');
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

    const NEXT_DEAL_MS = 900;
    const SHUFFLE_INTERVAL_MS = 70;
    const ROUND_RESET_MS = 1500;

    let playing = false;

    const cardLabel = highlowCardLabel;

    function setMultiplierLabels(higher, lower) {
        if (higherMultEl) higherMultEl.textContent = highlowFormatMultiplier(higher);
        if (lowerMultEl) lowerMultEl.textContent = highlowFormatMultiplier(lower);
    }

    function clearMultiplierLabels() {
        setMultiplierLabels(null, null);
    }

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
            replayDealAnimation(anchorFace);
            if (window.CasinoSounds) window.CasinoSounds.play('deal');
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
        nextCard.classList.add('shuffling');
        return setInterval(function () {
            nextFace.textContent = cardLabel(1 + Math.floor(Math.random() * 13));
        }, SHUFFLE_INTERVAL_MS);
    }

    function stopNextShuffle(intervalId, value) {
        clearInterval(intervalId);
        nextCard.classList.remove('shuffling');
        if (typeof value === 'number') {
            nextFace.textContent = cardLabel(value);
            nextCard.dataset.value = String(value);
            replayDealAnimation(nextFace);
            // Final card snaps into place — sounds the flip cue.
            if (window.CasinoSounds) window.CasinoSounds.play('flip');
        } else {
            nextFace.textContent = '?';
            delete nextCard.dataset.value;
        }
    }

    // Re-trigger the shared casino deal-in animation on a glyph that's
    // already in the DOM. casino-table.css applies the keyframe via
    // `.is-dealt`, so toggling the class restarts the 280ms ease-out.
    function replayDealAnimation(el) {
        if (!el) return;
        el.classList.remove('is-dealt');
        // Force reflow so the browser registers the class removal before
        // re-adding it — without this the animation just keeps its current
        // state instead of replaying.
        // eslint-disable-next-line no-unused-expressions
        el.offsetWidth;
        el.classList.add('is-dealt');
        setTimeout(function () { el.classList.remove('is-dealt'); }, 320);
    }

    // /start uses the shared form + TOBY-topup scaffolding. /play has
    // its own button pair below — different lifecycle, kept manual.
    const game = window.TobyCasinoGame.init({
        guildId: guildId,
        endpoint: '/casino/' + guildId + '/highlow/start',
        form: form,
        stakeInput: stakeInput,
        primaryBtn: dealBtn,
        tobyBtn: dealTobyBtn,
        balanceEl: balanceEl,
        resultEl: resultEl,
        tobyCoins: Number(main.dataset.tobyCoins) || 0,
        marketPrice: Number(main.dataset.marketPrice) || 0,
        failureMessage: 'Could not lock the round.',
        // /start doesn't settle credit/coin movement — it just locks
        // the stake and deals the anchor. Balance updates land via
        // /play's response instead.
        autoApplyBalance: false,
        renderResult: function (body) {
            if (typeof body.anchor === 'number') {
                showCallMode(body.anchor, body.higherMultiplier, body.lowerMultiplier);
            }
        },
    });

    function runPlay(direction) {
        if (playing) return;
        if (!postJson) {
            window.toast('API helper missing — refresh the page.', 'error');
            return;
        }

        playing = true;
        higherBtn.disabled = true;
        lowerBtn.disabled = true;
        const intervalId = startNextShuffle();
        const requestStart = Date.now();

        postJson('/casino/' + guildId + '/highlow/play', { direction: direction })
            .then(function (body) {
                const elapsed = Date.now() - requestStart;
                const remaining = Math.max(0, NEXT_DEAL_MS - elapsed);
                setTimeout(function () {
                    playing = false;
                    if (body && body.ok) {
                        stopNextShuffle(intervalId, body.next);
                        renderHighlowResult(resultEl, body);
                        if (window.CasinoSounds) {
                            window.CasinoSounds.play(body.net > 0 ? 'win' : 'lose');
                        }
                        // Drop a chip stack on the felt so a high-low win
                        // celebrates the same way a blackjack/poker win does.
                        // highlow uses `net > 0` as its win flag; synthesise
                        // the field flashWinPayout expects.
                        if (window.CasinoRender) {
                            window.CasinoRender.flashWinPayout(tableEl, {
                                win: body.net > 0,
                                net: body.net,
                                jackpotPayout: body.jackpotPayout,
                            });
                        }
                        if (game) {
                            game.applyBalance(body.newBalance);
                            game.applyTobyDelta(body);
                        }
                        // Round consumed → reset to lock mode after the
                        // player has had a moment to read the result.
                        setTimeout(showLockMode, ROUND_RESET_MS);
                    } else {
                        stopNextShuffle(intervalId);
                        higherBtn.disabled = false;
                        lowerBtn.disabled = false;
                        window.toast((body && body.error) || 'Deal failed.', 'error');
                    }
                }, remaining);
            })
            .catch(function () {
                stopNextShuffle(intervalId);
                playing = false;
                higherBtn.disabled = false;
                lowerBtn.disabled = false;
                window.toast('Network error.', 'error');
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
