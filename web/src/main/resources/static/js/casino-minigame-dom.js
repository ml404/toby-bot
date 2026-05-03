// Shared element-selection helper for the casino-minigame pages.
//
// Every per-game JS file used to repeat the same ~12 lines of DOM
// lookups using a `{prefix}-X` naming convention:
//
//   const main = document.querySelector('main[data-guild-id]');
//   const guildId = main.dataset.guildId;
//   const stakeInput = document.getElementById('dice-stake');
//   const rollBtn = document.getElementById('dice-roll');
//   const rollTobyBtn = document.getElementById('dice-roll-toby');
//   const balanceEl = document.getElementById('dice-balance');
//   const resultEl = document.getElementById('dice-result');
//   const form = document.getElementById('dice-bet');
//
// `TobyCasinoMinigameDom.standardElements(prefix, actionVerb)` returns
// the same shape every game needs in a single call. Returns null if
// the page hasn't booted (no `<main data-guild-id>`) so per-game JS
// can early-return without a separate guard.

(function (root) {
    'use strict';

    function standardElements(prefix, actionVerb) {
        const main = document.querySelector('main[data-guild-id]');
        if (!main) return null;
        return {
            main: main,
            guildId: main.dataset.guildId,
            tobyCoins: Number(main.dataset.tobyCoins) || 0,
            marketPrice: Number(main.dataset.marketPrice) || 0,
            form: document.getElementById(prefix + '-bet'),
            stakeInput: document.getElementById(prefix + '-stake'),
            primaryBtn: document.getElementById(prefix + '-' + actionVerb),
            tobyBtn: document.getElementById(prefix + '-' + actionVerb + '-toby'),
            balanceEl: document.getElementById(prefix + '-balance'),
            resultEl: document.getElementById(prefix + '-result'),
        };
    }

    const api = { standardElements: standardElements };
    if (root) root.TobyCasinoMinigameDom = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
