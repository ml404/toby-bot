// Pure-DOM render for a /scratch response. Hoisted out of the IIFE so
// the jest test in `scratch.test.js` can drive it without booting the
// page.
//
// Win/lose sound + chip flourish are now owned by the shared
// `casino-win-settle.js` helper; the IIFE below calls
// `game.applyWinSettle(activeCard)` from inside `revealCell` once the
// player has finished scratching. Keeping it here would duplicate the
// helper and risk drift if the formula changes.
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
    if (typeof window !== 'undefined' && window.TobyBalance) {
        window.TobyBalance.update(balanceEl, body.newBalance);
    }
}

(function () {
    'use strict';

    const els = window.TobyCasinoMinigameDom &&
        window.TobyCasinoMinigameDom.standardElements('scratch', 'buy');
    if (!els) return;

    const matchThreshold = parseInt(els.main.dataset.matchThreshold, 10) || 5;

    const cellsContainer = document.getElementById('scratch-cells');
    const cellButtons = Array.from(cellsContainer ? cellsContainer.querySelectorAll('.scratch-cell') : []);
    const revealBtn = document.getElementById('scratch-reveal-all');

    if (!els.form || !els.primaryBtn || !els.stakeInput || cellButtons.length === 0) return;

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
            renderScratchResult(els.resultEl, activeCard, matchThreshold, els.balanceEl);
            // Win/lose cue + chip flourish — fired AFTER the last cell
            // reveals so the chips pop on the *cells* the player has
            // been scratching (not the wider table). Jackpot wins get a
            // taller stack so the celebration reads as bigger.
            if (game) {
                game.applyWinSettle(activeCard, {
                    flashTarget: cellsContainer,
                    chipCount: function (body) {
                        var payout = body.jackpotPayout > 0 ? body.jackpotPayout : (body.net || 0);
                        var cap = body.jackpotPayout > 0 ? 10 : 7;
                        return Math.min(cap, Math.max(3, Math.ceil(payout / 100)));
                    },
                });
                // Now the credits visibly drop and the topup-coin
                // recompute catches up too — see autoApplyBalance: false
                // in the helper.
                game.applyTobyDelta(activeCard);
            }
            // And only now release the central jackpot-pool banner so it
            // doesn't tick before the suspense beat is over.
            if (window.TobyJackpot) window.TobyJackpot.releasePoolBanner();
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
        guildId: els.guildId,
        endpoint: '/casino/' + els.guildId + '/scratch/scratch',
        form: els.form,
        stakeInput: els.stakeInput,
        primaryBtn: els.primaryBtn,
        tobyBtn: els.tobyBtn,
        balanceEl: els.balanceEl,
        resultEl: els.resultEl,
        tobyCoins: els.tobyCoins,
        marketPrice: els.marketPrice,
        failureMessage: 'Buy failed.',
        // Scratch shows the result only after every cell has been
        // revealed, so the helper must NOT auto-update balance/coins on
        // response. revealCell() does it once the suspense is over.
        // Same reasoning for the win-settle cue + flourish — opt out
        // of the auto-fire and call game.applyWinSettle() ourselves
        // from inside revealCell so the chips pop with the final reveal.
        autoApplyBalance: false,
        autoWinSettle: false,
        startAnimation: function () {
            if (els.resultEl) els.resultEl.hidden = true;
            resetCells();
            // Hold the central jackpot-pool banner across the whole
            // user-driven reveal — released in revealCell once every
            // cell is uncovered (or on a server error in stopAnimation).
            // casino-game.js skips its own hold for scratch because
            // autoApplyBalance is false.
            if (window.TobyJackpot) window.TobyJackpot.holdPoolBanner();
            return null;
        },
        stopAnimation: function (_handle, body) {
            if (body && body.ok) {
                activeCard = body;
                if (revealBtn) revealBtn.hidden = false;
            } else {
                activeCard = null;
                if (revealBtn) revealBtn.hidden = true;
                // Server errored or returned a non-ok body — there's
                // no reveal coming, so drop the lock now.
                if (window.TobyJackpot) window.TobyJackpot.releasePoolBanner();
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
