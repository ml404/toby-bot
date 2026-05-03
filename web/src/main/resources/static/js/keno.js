// Pure-DOM render for a /play response. Hoisted out of the IIFE so a
// jest test can drive it without booting the page. Mirrors the shape of
// renderBaccaratResult — same `stagger` / `dealMs` / synchronous-fallback
// pattern so the staged reveal is fully test-controllable.
//
// On a staged path: the cells the server drew light up one-by-one at
// dealMs intervals (gold pulse if the draw matches a pick, dim grey
// otherwise). The result line + chip flourish + win/lose sound hold
// until every draw has landed.
function renderKenoResult(opts) {
    var resultEl = opts.resultEl;
    var statusEl = opts.statusEl;
    var gridEl = opts.gridEl;
    var flashTargetEl = opts.flashTargetEl;
    var stagger = opts.stagger !== false;
    var dealMs = stagger ? (opts.dealMs || 120) : 0;
    var body = opts.body;

    if (!body) return;

    var picksSet = new Set(body.picks || []);
    var draws = body.draws || [];

    // Reset every cell on the board: drop any previous-round drawn /
    // hit classes, restore the picked highlight for the picks the
    // server confirmed.
    if (gridEl) {
        var cells = gridEl.querySelectorAll('.keno-cell');
        for (var c = 0; c < cells.length; c++) {
            var cell = cells[c];
            cell.classList.remove('is-drawn', 'is-hit');
            var n = parseInt(cell.dataset.value, 10);
            cell.classList.toggle('is-picked', picksSet.has(n));
        }
    }

    function finalize() {
        if (statusEl) {
            statusEl.textContent = body.win
                ? 'Round complete — ' + body.hits + ' of ' + (body.picks || []).length + ' hit.'
                : (body.hits || 0) + ' of ' + (body.picks || []).length + ' hit — no payout this round.';
        }

        if (resultEl) {
            if (typeof window !== 'undefined' && window.TobyCasinoResult) {
                window.TobyCasinoResult.render({
                    resultEl: resultEl,
                    body: body,
                    classPrefix: 'keno',
                    winLineHtml: kenoWinLineHtml(body),
                    loseLineHtml: kenoLoseLineHtml(body),
                });
            }
        }

        if (typeof window !== 'undefined') {
            if (window.CasinoRender) {
                window.CasinoRender.flashWinPayout(flashTargetEl, body);
            }
            if (window.CasinoSounds) {
                window.CasinoSounds.play(body.win ? 'win' : 'lose');
            }
        }
    }

    // Hide any prior result line while the deal plays out — it'll be
    // re-rendered in finalize().
    if (resultEl && dealMs > 0) {
        resultEl.hidden = true;
        resultEl.classList.remove('keno-result-win', 'keno-result-lose', 'keno-result-jackpot');
        resultEl.innerHTML = '';
    }
    if (statusEl && dealMs > 0) {
        statusEl.textContent = 'Drawing…';
    }

    if (!gridEl || dealMs <= 0 || draws.length === 0) {
        // Synchronous path — light up every drawn cell at once. Tests
        // pass `stagger: false`; production also falls through here if
        // the grid element is missing (defensive).
        for (var d = 0; d < draws.length; d++) {
            lightUpDraw(gridEl, draws[d], picksSet);
        }
        finalize();
        return;
    }

    // Staged path: schedule one cell-light-up per dealMs. The 'click'
    // sound rides the per-cell tick so the player can hear the deal.
    draws.forEach(function (n, i) {
        setTimeout(function () {
            lightUpDraw(gridEl, n, picksSet);
            if (typeof window !== 'undefined' && window.CasinoSounds) {
                window.CasinoSounds.play('click');
            }
        }, i * dealMs);
    });
    setTimeout(finalize, draws.length * dealMs);
}

function lightUpDraw(gridEl, n, picksSet) {
    if (!gridEl) return;
    var cell = gridEl.querySelector('.keno-cell[data-value="' + n + '"]');
    if (!cell) return;
    if (picksSet && picksSet.has(n)) {
        // Hit — gold pulse via casino-pulse keyframe.
        cell.classList.remove('is-picked', 'is-drawn');
        cell.classList.add('is-hit');
    } else {
        cell.classList.remove('is-picked');
        cell.classList.add('is-drawn');
    }
}

function kenoWinLineHtml(body) {
    var multTxt = (typeof body.multiplier === 'number')
        ? body.multiplier.toFixed(body.multiplier >= 10 ? 0 : 2) + '×'
        : '';
    return '🎯 <strong>' + body.hits + '/' + (body.picks || []).length +
        '</strong> hit · won <strong>+' + body.net + ' credits</strong>' +
        (multTxt ? ' at ' + multTxt : '') + '.';
}

function kenoLoseLineHtml(body) {
    return '❌ <strong>' + (body.hits || 0) + '/' + (body.picks || []).length +
        '</strong> hit · lost <strong>' + Math.abs(body.net) + ' credits</strong>.';
}

(function () {
    'use strict';

    var main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    var guildId = main.dataset.guildId;
    var minSpots = parseInt(main.dataset.minSpots, 10) || 1;
    var maxSpots = parseInt(main.dataset.maxSpots, 10) || 10;

    var stakeInput = document.getElementById('keno-stake');
    var dealBtn = document.getElementById('keno-deal');
    var dealTobyBtn = document.getElementById('keno-deal-toby');
    var balanceEl = document.getElementById('keno-balance');
    var resultEl = document.getElementById('keno-result');
    var form = document.getElementById('keno-bet-form');
    var tableEl = document.querySelector('.keno-table');
    var gridEl = document.getElementById('keno-grid');
    var statusEl = document.getElementById('keno-status');
    var pickCountEl = document.getElementById('keno-pickcount-value');
    var quickPickBtn = document.getElementById('keno-quickpick');
    var clearBtn = document.getElementById('keno-clear');

    if (!form || !dealBtn || !stakeInput || !gridEl) return;

    var picks = new Set();

    function refreshPickCount() {
        if (pickCountEl) pickCountEl.textContent = String(picks.size);
    }

    function updateCellPicked(cell, picked) {
        cell.classList.toggle('is-picked', picked);
        cell.setAttribute('aria-pressed', picked ? 'true' : 'false');
    }

    function clearRoundDecorations() {
        // Strip drawn / hit decorations from the previous round so a
        // fresh ticket starts on a clean board.
        var marked = gridEl.querySelectorAll('.is-drawn, .is-hit');
        for (var i = 0; i < marked.length; i++) {
            marked[i].classList.remove('is-drawn', 'is-hit');
        }
    }

    function togglePick(cell) {
        var n = parseInt(cell.dataset.value, 10);
        if (Number.isNaN(n)) return;
        if (picks.has(n)) {
            picks.delete(n);
            updateCellPicked(cell, false);
        } else {
            if (picks.size >= maxSpots) {
                if (window.toast) {
                    window.toast('Maximum ' + maxSpots + ' picks per ticket.', 'info');
                }
                return;
            }
            picks.add(n);
            updateCellPicked(cell, true);
        }
        refreshPickCount();
    }

    function clearPicks() {
        var picked = gridEl.querySelectorAll('.keno-cell.is-picked');
        for (var i = 0; i < picked.length; i++) updateCellPicked(picked[i], false);
        picks.clear();
        refreshPickCount();
    }

    function quickPickRest() {
        // Auto-fill up to maxSpots from the cells the user hasn't picked.
        var available = [];
        var cells = gridEl.querySelectorAll('.keno-cell');
        for (var i = 0; i < cells.length; i++) {
            var n = parseInt(cells[i].dataset.value, 10);
            if (!picks.has(n)) available.push(n);
        }
        // Fisher-Yates shuffle, but we only need maxSpots-picks.size values
        // so partial-shuffle is plenty.
        var need = maxSpots - picks.size;
        if (need <= 0) return;
        for (var k = 0; k < need && available.length > 0; k++) {
            var idx = Math.floor(Math.random() * available.length);
            var pick = available[idx];
            available.splice(idx, 1);
            picks.add(pick);
            var cell = gridEl.querySelector('.keno-cell[data-value="' + pick + '"]');
            if (cell) updateCellPicked(cell, true);
        }
        refreshPickCount();
    }

    // Wire cell clicks (delegated for cheap setup over 80 cells).
    gridEl.addEventListener('click', function (e) {
        var cell = e.target.closest('.keno-cell');
        if (!cell || !gridEl.contains(cell)) return;
        togglePick(cell);
    });

    if (quickPickBtn) quickPickBtn.addEventListener('click', quickPickRest);
    if (clearBtn) clearBtn.addEventListener('click', clearPicks);

    if (window.TobyCasinoGame) {
        window.TobyCasinoGame.init({
            guildId: guildId,
            endpoint: '/casino/' + guildId + '/keno/play',
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
                if (picks.size < minSpots) return 'Pick at least ' + minSpots + ' number' + (minSpots === 1 ? '' : 's') + '.';
                if (picks.size > maxSpots) return 'Pick at most ' + maxSpots + ' numbers.';
                return null;
            },
            buildPayload: function (state) {
                return {
                    picks: Array.from(picks).sort(function (a, b) { return a - b; }),
                    stake: state.stake,
                    autoTopUp: state.autoTopUp,
                };
            },
            startAnimation: function () {
                clearRoundDecorations();
                return null;
            },
            renderResult: function (body) {
                renderKenoResult({
                    resultEl: resultEl,
                    statusEl: statusEl,
                    gridEl: gridEl,
                    flashTargetEl: tableEl,
                    body: body,
                });
            },
        });
    }

    refreshPickCount();
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { renderKenoResult, kenoWinLineHtml, kenoLoseLineHtml };
}
