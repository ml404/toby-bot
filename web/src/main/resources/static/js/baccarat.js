// Pure-DOM render for a /play response. Hoisted out of the IIFE so a
// future jest test can drive it without booting the page. Mirrors the
// shape of renderCoinflipResult / renderHighlowResult.
function renderBaccaratResult(opts) {
    var resultEl = opts.resultEl;
    var bankerCardsEl = opts.bankerCardsEl;
    var playerCardsEl = opts.playerCardsEl;
    var bankerTotalEl = opts.bankerTotalEl;
    var playerTotalEl = opts.playerTotalEl;
    var tableEl = opts.tableEl;
    var body = opts.body;

    if (tableEl) tableEl.hidden = false;

    // Card glyphs come from the shared casino-render module (red ♥/♦
    // colouring + deal animation are automatic). Bail to plain text if
    // the module is missing (e.g. in jsdom unit tests).
    var render = (typeof window !== 'undefined' && window.CasinoRender)
        ? window.CasinoRender.renderCards
        : null;
    if (render) {
        if (bankerCardsEl) render(bankerCardsEl, body.bankerCards || []);
        if (playerCardsEl) render(playerCardsEl, body.playerCards || []);
    } else {
        if (bankerCardsEl) bankerCardsEl.textContent = (body.bankerCards || []).join(' ');
        if (playerCardsEl) playerCardsEl.textContent = (body.playerCards || []).join(' ');
    }

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
                    body: body,
                });
            },
        });
    }
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderBaccaratResult };
}
