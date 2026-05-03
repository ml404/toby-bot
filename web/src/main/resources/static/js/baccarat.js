// Pure-DOM render for a /play response. Hoisted out of the IIFE so a
// future jest test can drive it without booting the page. Mirrors the
// shape of renderCoinflipResult / renderHighlowResult.
//
// Staging: opts.stagger (default true) walks the cards onto the felt in
// Punto Banco tableau order (P1 → B1 → P2 → B2 → optional P3 → B3) at
// opts.dealMs intervals (default 400ms) so the round feels played, not
// pre-resolved. Tests pass `stagger: false` to keep their assertions
// synchronous. The result line / chip flourish / win-or-lose sound are
// held until the last card lands.
function renderBaccaratResult(opts) {
    var resultEl = opts.resultEl;
    var bankerCardsEl = opts.bankerCardsEl;
    var playerCardsEl = opts.playerCardsEl;
    var bankerTotalEl = opts.bankerTotalEl;
    var playerTotalEl = opts.playerTotalEl;
    var tableEl = opts.tableEl;
    var flashTargetEl = opts.flashTargetEl || tableEl;
    var stagger = opts.stagger !== false;
    var dealMs = stagger ? (opts.dealMs || 400) : 0;
    var body = opts.body;

    if (tableEl) tableEl.hidden = false;

    // Quiet the totals while the deal is in flight — finalize() restores
    // them with the natural tags once the last card lands. (Synchronous
    // path keeps today's behaviour: totals are set inside finalize itself.)
    if (dealMs > 0) {
        if (bankerTotalEl) bankerTotalEl.textContent = '';
        if (playerTotalEl) playerTotalEl.textContent = '';
    }

    function finalize() {
        if (bankerTotalEl) {
            var bankerTag = body.isBankerNatural ? ' • Natural' : '';
            bankerTotalEl.textContent = '(' + body.bankerTotal + bankerTag + ')';
        }
        if (playerTotalEl) {
            var playerTag = body.isPlayerNatural ? ' • Natural' : '';
            playerTotalEl.textContent = '(' + body.playerTotal + playerTag + ')';
        }

        if (!resultEl) return;
        var sideLabel = sideName(body.side);
        var winnerLabel = sideName(body.winner);

        if (body.push) {
            // Tied game on a Player/Banker bet — stake refunded, neither
            // win nor lose. Skip the shared TobyCasinoResult helper because
            // it only knows the win/lose dichotomy; render the push line
            // directly so the colour and class can be -push.
            resultEl.hidden = false;
            resultEl.classList.remove('bac-result-win', 'bac-result-lose', 'bac-result-jackpot');
            resultEl.classList.add('bac-result-push');
            var topUpPrefix = (typeof window !== 'undefined' && window.TobyTopUp)
                ? window.TobyTopUp.soldPrefixHtml(body.soldTobyCoins, body.newPrice)
                : '';
            resultEl.innerHTML = topUpPrefix +
                '🤝 <strong>Tie game.</strong> Your <strong>' + sideLabel + '</strong> stake of ' +
                '<strong>' + body.payout + ' credits</strong> is refunded.';
            return;
        }

        if (typeof window !== 'undefined' && window.TobyCasinoResult) {
            window.TobyCasinoResult.render({
                resultEl: resultEl,
                body: body,
                classPrefix: 'bac',
                winLineHtml: winLineHtml(body, sideLabel, winnerLabel),
                loseLineHtml: loseLineHtml(body, sideLabel, winnerLabel),
            });
        }

        // Match the blackjack/poker payoff flourish: gold chip stack pops on
        // wins, sound cue on either outcome. flashWinPayout picks the right
        // payout (jackpot > net) and no-ops on losses.
        if (typeof window !== 'undefined') {
            if (window.CasinoRender) {
                window.CasinoRender.flashWinPayout(flashTargetEl, body);
            }
            if (window.CasinoSounds) {
                window.CasinoSounds.play(body.win ? 'win' : 'lose');
            }
        }
    }

    playBaccaratDeal({
        playerCardsEl: playerCardsEl,
        bankerCardsEl: bankerCardsEl,
        body: body,
        dealMs: dealMs,
        onComplete: finalize,
    });
}

// Walks the two hands onto the felt in Punto Banco tableau order. With
// dealMs > 0 the steps are scheduled via setTimeout; with dealMs === 0
// (or no CasinoRender available) the whole thing runs synchronously and
// onComplete fires in the same tick.
//
// Relies on CasinoRender.renderCards's per-container previousCount so
// re-rendering with a growing array animates only the freshly-arrived
// card; the per-card 'deal' click cue rides along inside that helper.
function playBaccaratDeal(opts) {
    var playerCardsEl = opts.playerCardsEl;
    var bankerCardsEl = opts.bankerCardsEl;
    var body = opts.body;
    var dealMs = opts.dealMs;
    var onComplete = opts.onComplete;
    var pCards = body.playerCards || [];
    var bCards = body.bankerCards || [];

    var renderCards = (typeof window !== 'undefined' && window.CasinoRender)
        ? window.CasinoRender.renderCards
        : null;

    function done() { if (onComplete) onComplete(); }

    if (!renderCards || !dealMs || dealMs <= 0) {
        // Synchronous path — preserves the legacy "paint everything at
        // once" behaviour and keeps the existing jest tests trivial.
        if (renderCards) {
            if (bankerCardsEl) renderCards(bankerCardsEl, bCards);
            if (playerCardsEl) renderCards(playerCardsEl, pCards);
        } else {
            if (bankerCardsEl) bankerCardsEl.textContent = bCards.join(' ');
            if (playerCardsEl) playerCardsEl.textContent = pCards.join(' ');
        }
        done();
        return;
    }

    // Build the tableau order: alternating Player / Banker, growing each
    // side's array by one card at each step. Trailing nulls (one side
    // ended sooner than the other) are pruned so onComplete fires the
    // moment the last real card lands.
    var steps = [];
    var maxLen = Math.max(pCards.length, bCards.length);
    for (var i = 0; i < maxLen; i++) {
        steps.push(i < pCards.length ? { el: playerCardsEl, list: pCards.slice(0, i + 1) } : null);
        steps.push(i < bCards.length ? { el: bankerCardsEl, list: bCards.slice(0, i + 1) } : null);
    }
    while (steps.length && steps[steps.length - 1] === null) steps.pop();

    // Wipe both sides up front so a fresh round starts on a clear felt.
    // (renderCards([]) resets the previousCount tracker, so the first
    // dealt card animates in cleanly.)
    if (playerCardsEl) renderCards(playerCardsEl, []);
    if (bankerCardsEl) renderCards(bankerCardsEl, []);

    steps.forEach(function (step, i) {
        setTimeout(function () {
            if (step && step.el) renderCards(step.el, step.list);
        }, i * dealMs);
    });
    setTimeout(done, steps.length * dealMs);
}

function sideName(raw) {
    if (raw === 'PLAYER') return 'Player';
    if (raw === 'BANKER') return 'Banker';
    if (raw === 'TIE') return 'Tie';
    return String(raw || '');
}

function winLineHtml(body, sideLabel, winnerLabel) {
    var multTxt = (typeof body.multiplier === 'number')
        ? body.multiplier.toFixed(2) + '×'
        : '';
    var prefix = body.side === 'TIE' ? '🎉 <strong>Tie!</strong>' :
        body.side === 'BANKER' ? '⚖️ <strong>Banker wins (5% commission).</strong>' :
        '✅ <strong>Player wins.</strong>';
    return prefix + ' Won <strong>+' + body.net + ' credits</strong> at ' + multTxt + '.';
}

function loseLineHtml(body, sideLabel, winnerLabel) {
    return '❌ <strong>' + winnerLabel + ' wins.</strong> You called <strong>' +
        sideLabel + '</strong> · lost <strong>' + Math.abs(body.net) + ' credits</strong>.';
}

(function () {
    'use strict';

    var main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    var guildId = main.dataset.guildId;
    var stakeInput = document.getElementById('bac-stake');
    var dealBtn = document.getElementById('bac-deal');
    var dealTobyBtn = document.getElementById('bac-deal-toby');
    var balanceEl = document.getElementById('bac-balance');
    var resultEl = document.getElementById('bac-result');
    var form = document.getElementById('bac-bet');
    var tableEl = document.getElementById('bac-table');
    var bankerCardsEl = document.getElementById('bac-banker-cards');
    var playerCardsEl = document.getElementById('bac-player-cards');
    var bankerTotalEl = document.getElementById('bac-banker-total');
    var playerTotalEl = document.getElementById('bac-player-total');

    if (!form || !dealBtn || !stakeInput) return;

    function selectedSide() {
        var checked = form.querySelector('input[name="side"]:checked');
        return checked ? checked.value : null;
    }

    if (window.TobyCasinoGame) {
        window.TobyCasinoGame.init({
            guildId: guildId,
            endpoint: '/casino/' + guildId + '/baccarat/play',
            form: form,
            stakeInput: stakeInput,
            primaryBtn: dealBtn,
            tobyBtn: dealTobyBtn,
            balanceEl: balanceEl,
            resultEl: resultEl,
            tobyCoins: Number(main.dataset.tobyCoins) || 0,
            marketPrice: Number(main.dataset.marketPrice) || 0,
            failureMessage: 'Deal failed.',
            validate: function () {
                if (!selectedSide()) return 'Pick a side first.';
                return null;
            },
            buildPayload: function (state) {
                return { side: selectedSide(), stake: state.stake, autoTopUp: state.autoTopUp };
            },
            renderResult: function (body) {
                renderBaccaratResult({
                    resultEl: resultEl,
                    bankerCardsEl: bankerCardsEl,
                    playerCardsEl: playerCardsEl,
                    bankerTotalEl: bankerTotalEl,
                    playerTotalEl: playerTotalEl,
                    tableEl: tableEl,
                    flashTargetEl: tableEl,
                    body: body,
                });
            },
        });
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderBaccaratResult, playBaccaratDeal };
}
