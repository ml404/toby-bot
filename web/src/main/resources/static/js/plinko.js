// Pure-DOM render for a /drop response. Hoisted out of the IIFE for tests.
function renderPlinkoResult(resultEl, body) {
    if (typeof window === 'undefined' || !window.TobyCasinoResult) return;
    const mult = (body && typeof body.multiplier === 'number')
        ? body.multiplier.toFixed(2).replace(/\.?0+$/, '') + '×'
        : '?';
    const bucket = (body && typeof body.bucket === 'number') ? body.bucket : '?';
    const isPush = !!(body && body.push);
    const winLine = '<strong>Bucket ' + bucket + ' &middot; ' + mult + '</strong>' +
        (body && typeof body.net === 'number'
            ? ' &middot; <strong>+' + body.net + ' credits</strong>'
            : '');
    let loseLine = '<strong>Bucket ' + bucket + ' &middot; ' + mult + '</strong>';
    if (isPush) {
        loseLine += ' &middot; <span>refund — net 0</span>';
    } else if (body && typeof body.net === 'number') {
        loseLine += ' &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>';
    }
    window.TobyCasinoResult.render({
        resultEl: resultEl,
        body: body,
        classPrefix: 'plinko',
        winLineHtml: winLine,
        loseLineHtml: loseLine,
    });
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('plinko', 'drop');
    if (!els) return;

    const board = document.getElementById('plinko-board');
    const ball = document.getElementById('plinko-ball');
    const bucketsEl = document.getElementById('plinko-buckets');
    const tableEl = document.querySelector('.plinko-table');

    if (!els.form || !els.primaryBtn || !els.stakeInput || !board || !ball || !bucketsEl) return;

    const DROP_DURATION_MS = 900;

    function selectedRisk() {
        const checked = els.form.querySelector('input[name="risk"]:checked');
        return checked ? checked.value : null;
    }

    function startDropAnimation() {
        ball.hidden = false;
        board.classList.add('dropping');
        if (window.CasinoSounds) window.CasinoSounds.play('deal');
    }

    function stopDropAnimation(_intervalId, body) {
        board.classList.remove('dropping');
        if (body && typeof body.bucket === 'number') {
            // Highlight the landed bucket; CSS owns the bounce.
            bucketsEl.querySelectorAll('.plinko-bucket').forEach(function (el) {
                el.classList.toggle(
                    'plinko-bucket-landed',
                    Number(el.dataset.index) === body.bucket
                );
            });
            if (window.CasinoSounds) window.CasinoSounds.play('click');
        }
        ball.hidden = true;
    }

    window.TobyCasinoGame.init({
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/plinko/drop',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        minSettleMs: DROP_DURATION_MS,
        failureMessage: 'Drop failed.',
        validate: function () {
            if (!selectedRisk()) return 'Pick a risk profile first.';
            return null;
        },
        buildPayload: function (state) {
            return {
                stake: state.stake,
                risk: selectedRisk(),
                autoTopUp: state.autoTopUp,
            };
        },
        startAnimation: startDropAnimation,
        stopAnimation: stopDropAnimation,
        renderResult: function (body) { renderPlinkoResult(els.resultEl, body); },
        flashTarget: tableEl,
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderPlinkoResult };
}
