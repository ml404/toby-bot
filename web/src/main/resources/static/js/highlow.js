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
//
// flashTargetEl is the felt element that gets the chip-stack flourish
// on a win — passing it through the render fn keeps the chip flourish
// callable from tests, instead of being trapped inside the IIFE.
function renderHighlowResult(resultEl, body, flashTargetEl) {
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
    // Highlow's response has no `win` field — net > 0 is the win signal.
    if (window.CasinoRender) {
        window.CasinoRender.flashWinPayout(flashTargetEl, {
            win: (body.net || 0) > 0,
            net: body.net,
            jackpotPayout: body.jackpotPayout,
        });
    }
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('highlow', 'deal');
    if (!els) return;

    const postJson = window.TobyApi && window.TobyApi.postJson;

    const anchorFace = document.getElementById('highlow-anchor-face');
    const nextFace = document.getElementById('highlow-next-face');
    const anchorCard = document.getElementById('highlow-anchor');
    const nextCard = document.getElementById('highlow-next');
    const tableEl = document.querySelector('.highlow-table');
    const directionPicker = document.getElementById('highlow-direction-picker');
    const higherBtn = document.getElementById('highlow-call-higher');
    const lowerBtn = document.getElementById('highlow-call-lower');
    const higherMultEl = document.getElementById('highlow-call-higher-mult');
    const lowerMultEl = document.getElementById('highlow-call-lower-mult');

    if (!els.form || !els.primaryBtn || !els.stakeInput || !anchorFace || !nextFace) return;
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
        els.form.hidden = false;
        els.primaryBtn.disabled = false;
        if (els.tobyBtn) els.tobyBtn.disabled = false;
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
        els.form.hidden = true;
        directionPicker.hidden = false;
        higherBtn.disabled = false;
        lowerBtn.disabled = false;
        setMultiplierLabels(higherMult, lowerMult);
        if (els.resultEl) els.resultEl.hidden = true;
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
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/highlow/start',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
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

        // /play has a different lifecycle than /start (button pair, not
        // form submit) so it goes through casino-game.js's runManual
        // helper instead of init(). The helper handles the jackpot-pool
        // lock + settle delay so the banner can't tick before the
        // next-card shuffle lands.
        window.TobyCasinoGame.runManual({
            request: function () {
                return postJson('/casino/' + els.guildId + '/highlow/play', { direction: direction });
            },
            settleMs: NEXT_DEAL_MS,
            onSettle: function (body) {
                playing = false;
                if (body && body.ok) {
                    stopNextShuffle(intervalId, body.next);
                    renderHighlowResult(els.resultEl, body, tableEl);
                    if (window.CasinoSounds) {
                        window.CasinoSounds.play(body.net > 0 ? 'win' : 'lose');
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
            },
            onError: function () {
                stopNextShuffle(intervalId);
                playing = false;
                higherBtn.disabled = false;
                lowerBtn.disabled = false;
                window.toast('Network error.', 'error');
            },
        });
    }

    higherBtn.addEventListener('click', function () { runPlay('HIGHER'); });
    lowerBtn.addEventListener('click', function () { runPlay('LOWER'); });

    // If the server pre-rendered an active round (page refresh mid-round),
    // jump straight to call mode so the player can finish their bet.
    const preloadedAnchor = parseInt(els.main.dataset.activeAnchor || '', 10);
    if (Number.isFinite(preloadedAnchor) && preloadedAnchor > 0) {
        const preloadedHigher = parseFloat(els.main.dataset.higherMultiplier || '');
        const preloadedLower = parseFloat(els.main.dataset.lowerMultiplier || '');
        showCallMode(preloadedAnchor, preloadedHigher, preloadedLower);
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderHighlowResult, highlowCardLabel, highlowFormatMultiplier };
}
