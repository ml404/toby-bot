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

    const cellsContainer = document.getElementById('scratch-cells');
    const cellButtons = Array.from(cellsContainer ? cellsContainer.querySelectorAll('.scratch-cell') : []);
    const stakeInput = document.getElementById('scratch-stake');
    const buyBtn = document.getElementById('scratch-buy');
    const revealBtn = document.getElementById('scratch-reveal-all');
    const balanceEl = document.getElementById('scratch-balance');
    const resultEl = document.getElementById('scratch-result');
    const form = document.getElementById('scratch-bet');

    if (!form || !buyBtn || !stakeInput || cellButtons.length === 0) return;

    let busy = false;
    // Latest active card. Server returns the symbols up front; cells are
    // hidden until the user scratches each one. Once all are revealed (or
    // they hit "Reveal all") the result is shown.
    let activeCard = null;

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
        if (activeCard.winningSymbol && activeCard.cells[index] === activeCard.winningSymbol) {
            btn.classList.add('win-cell');
        }
        if (cellButtons.every(function (b) { return b.classList.contains('revealed'); })) {
            showResult(activeCard);
            // Card consumed — next "Buy ticket" starts a fresh one.
            activeCard = null;
            if (revealBtn) revealBtn.hidden = true;
        }
    }

    function revealAll() {
        if (!activeCard) return;
        cellButtons.forEach(function (_, i) { revealCell(i); });
    }

    function showResult(body) {
        if (!resultEl) return;
        resultEl.hidden = false;
        resultEl.classList.remove('scratch-result-win', 'scratch-result-lose');
        if (body.win) {
            resultEl.classList.add('scratch-result-win');
            resultEl.innerHTML = '<strong>' + body.matchCount + '× ' + body.winningSymbol +
                '</strong> &middot; <strong>+' + body.net + ' credits</strong>';
        } else {
            resultEl.classList.add('scratch-result-lose');
            resultEl.innerHTML = 'No 3-of-a-kind &middot; lost <strong>' + Math.abs(body.net) + ' credits</strong>';
        }
        if (typeof body.newBalance === 'number' && balanceEl) balanceEl.textContent = body.newBalance;
    }

    cellButtons.forEach(function (btn) {
        btn.addEventListener('click', function () {
            const idx = parseInt(btn.dataset.index, 10);
            revealCell(idx);
        });
    });

    if (revealBtn) revealBtn.addEventListener('click', revealAll);

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        if (busy) return;

        const stake = parseInt(stakeInput.value, 10);
        if (!stake || stake <= 0) {
            toast('Enter a positive stake.', 'error');
            return;
        }

        busy = true;
        buyBtn.disabled = true;
        if (resultEl) resultEl.hidden = true;
        resetCells();

        if (!postJson) {
            buyBtn.disabled = false;
            busy = false;
            toast('API helper missing — refresh the page.', 'error');
            return;
        }

        postJson('/casino/' + guildId + '/scratch/scratch', { stake: stake })
            .then(function (body) {
                buyBtn.disabled = false;
                busy = false;
                if (body && body.ok) {
                    activeCard = body;
                    if (revealBtn) revealBtn.hidden = false;
                    // Don't auto-reveal — the user clicks each cell. The
                    // result and balance only apply when all cells are
                    // scratched (or "Reveal all" is hit), so the player
                    // gets the suspense beat.
                } else {
                    activeCard = null;
                    if (revealBtn) revealBtn.hidden = true;
                    toast((body && body.error) || 'Buy failed.', 'error');
                }
            })
            .catch(function () {
                buyBtn.disabled = false;
                busy = false;
                toast('Network error.', 'error');
            });
    });
})();
