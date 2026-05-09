// Shared helper for the casino-side anti-autoclicker tracker. Each
// minigame's IIFE creates one tracker via TobyCasinoBotSuspicion.createTracker(),
// hooks document `mousemove` and the bet button's `click` event, then
// snapshots the signals into the bet POST's buildPayload. Backend pairs
// these with CasinoBotSuspicionService to drive the per-(user, guild,
// gameKey) streak.

(function (root) {
    'use strict';

    function createTracker() {
        let mouseMoved = false;
        let lastClickX = null;
        let lastClickY = null;
        return {
            recordMouseMove: function () { mouseMoved = true; },
            recordClick: function (event) {
                if (!event) return;
                lastClickX = typeof event.clientX === 'number' ? event.clientX : null;
                lastClickY = typeof event.clientY === 'number' ? event.clientY : null;
            },
            snapshotAndReset: function () {
                const snapshot = {
                    clickX: lastClickX,
                    clickY: lastClickY,
                    mouseMoved: mouseMoved,
                };
                // Frontend reports motion *between* bets, not since page load.
                // Coords stay set so the next click overwrites naturally; if
                // none arrives (e.g. keyboard submit) the prior coords are
                // resent, but the backend's null-handling resets the streak
                // on a missing field anyway.
                mouseMoved = false;
                return snapshot;
            },
        };
    }

    const api = { createTracker: createTracker };

    // Browser global so each game's IIFE can reach it without bundler
    // plumbing — same pattern as TobyJackpot, TobyCasinoResult, etc.
    if (root) root.TobyCasinoBotSuspicion = api;

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
