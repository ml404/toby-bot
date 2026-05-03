// Pure-DOM render for a /scratch response. Hoisted out of the IIFE so
// the jest test in `scratch.test.js` can drive it without booting the
// page. matchThreshold and balanceEl are passed in (they live as DOM
// attributes / element references inside the IIFE) to keep this fully
// stateless.
function renderScratchResult(resultEl, body, matchThreshold, balanceEl) {
    if (typeof window !== 'undefined' && window.TobyCasinoResult) {
        window.TobyCasinoResult.render({
            resultEl: resultEl,
            body: body,
            classPrefix: 'scratch',
            winLineHtml: '<strong>' + body.matchCount + '× ' + body.winningSymbol +
                '</strong> &middot; <strong>+' + body.net + ' credits</strong>',
            loseLineHtml: 'No ' + matchThreshold + '-of-a-kind &middot; lost <strong>' +
                Math.abs(body.net) + ' credits</strong>',
        });
    }
    if (typeof body.newBalance === 'number' && balanceEl) balanceEl.textContent = body.newBalance;
}

(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const matchThreshold = parseInt(main.dataset.matchThreshold, 10) || 5;
    const initialTobyCoins = Number(main.dataset.tobyCoins) || 0;

    const cellsContainer = document.getElementById('scratch-cells');
    const cellButtons = Array.from(cellsContainer ? cellsContainer.querySelectorAll('.scratch-cell') : []);
    const tableEl = document.querySelector('.scratch-table');
    const stakeInput = document.getElementById('scratch-stake');
    const buyBtn = document.getElementById('scratch-buy');
    const buyTobyBtn = document.getElementById('scratch-buy-toby');
    const revealBtn = document.getElementById('scratch-reveal-all');
    const balanceEl = document.getElementById('scratch-balance');
    const resultEl = document.getElementById('scratch-result');
    const form = document.getElementById('scratch-bet');

    if (!form || !buyBtn || !stakeInput || cellButtons.length === 0) return;

    // Latest active card. Server returns the symbols up front; cells are
    // hidden until the user scratches each one. Once all are revealed (or
    // they hit "Reveal all") the result is shown.
    let activeCard = null;
    let game;

    function resetCells() {
        cellButtons.forEach(function (btn) {
            btn.classList.remove('revealed', 'win-cell');
            btn.disabled = false;
            const cover = btn.querySelector('.scratch-cell-cover');
            const face = btn.querySelector('.scratch-cell-face');
            if (cover) cover.textContent = '?';
            if (face) face.textContent = '';
        });
    }

    function revealCell(index) {
        if (!activeCard) return;
        const btn = cellButtons[index];
        if (!btn || btn.classList.contains('revealed')) return;
        btn.classList.add('revealed');
        btn.disabled = true;
        const face = btn.querySelector('.scratch-cell-face');
        if (face) face.textContent = activeCard.cells[index] || '?';
        if (window.CasinoSounds) window.CasinoSounds.play('click');
        // Don't paint the winning cells green yet — that would spoil the
        // outcome before the user has finished scratching. We tag the
        // win cells once everything is revealed (see below).
        if (cellButtons.every(function (b) { return b.classList.contains('revealed'); })) {
            highlightWinCells(activeCard);
            renderScratchResult(resultEl, activeCard, matchThreshold, balanceEl);
            if (window.CasinoSounds) {
                window.CasinoSounds.play((activeCard.net || 0) > 0 ? 'win' : 'lose');
            }
            // Drop the shared chip flourish on the felt for a win, so a
            // scratchcard payout celebrates the same way every other
            // casino game does. flashWinPayout treats `win: true` and a
            // positive net as the trigger; scratch uses `net > 0` as its
            // win flag so we synthesise that field for the helper.
            if (window.CasinoRender) {
                var payoutEstimate = (activeCard.jackpotPayout > 0
                    ? activeCard.jackpotPayout
                    : (activeCard.net || 0));
                window.CasinoRender.flashWinPayout(tableEl, {
                    win: (activeCard.net || 0) > 0,
                    net: activeCard.net,
                    jackpotPayout: activeCard.jackpotPayout,
                }, Math.min(7, Math.max(3, Math.ceil(payoutEstimate / 100))));
            }
            // Now the credits visibly drop and the topup-coin recompute
            // catches up too — see autoApplyBalance: false in the helper.
            if (game) game.applyTobyDelta(activeCard);
            // Card consumed — next "Buy ticket" starts a fresh one.
            activeCard = null;
            if (revealBtn) revealBtn.hidden = true;
        }
    }

    function highlightWinCells(body) {
        if (!body || !body.winningSymbol) return;
        cellButtons.forEach(function (btn, i) {
            if (body.cells[i] === body.winningSymbol) {
                btn.classList.add('win-cell');
            }
        });
    }

    function revealAll() {
        if (!activeCard) return;
        // Stagger so the scratch-off animation cascades across the grid
        // instead of all 9 cells popping open simultaneously.
        cellButtons.forEach(function (_, i) {
            setTimeout(function () { revealCell(i); }, i * 60);
        });
    }

    cellButtons.forEach(function (btn) {
        btn.addEventListener('click', function () {
            const idx = parseInt(btn.dataset.index, 10);
            revealCell(idx);
        });
    });

    if (revealBtn) revealBtn.addEventListener('click', revealAll);

    game = window.TobyCasinoGame.init({
        guildId: guildId,
        endpoint: '/casino/' + guildId + '/scratch/scratch',
        form: form,
        stakeInput: stakeInput,
        primaryBtn: buyBtn,
        tobyBtn: buyTobyBtn,
        balanceEl: balanceEl,
        resultEl: resultEl,
        tobyCoins: initialTobyCoins,
        marketPrice: Number(main.dataset.marketPrice) || 0,
        failureMessage: 'Buy failed.',
        // Scratch shows the result only after every cell has been
        // revealed, so the helper must NOT auto-update balance/coins on
        // response. revealCell() does it once the suspense is over.
        autoApplyBalance: false,
        startAnimation: function () {
            if (resultEl) resultEl.hidden = true;
            resetCells();
            return null;
        },
        stopAnimation: function (_handle, body) {
            if (body && body.ok) {
                activeCard = body;
                if (revealBtn) revealBtn.hidden = false;
            } else {
                activeCard = null;
                if (revealBtn) revealBtn.hidden = true;
            }
        },
        renderResult: function () {
            // Deliberately a no-op — see revealCell.
        },
    });
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderScratchResult };
}
