// Pure helpers for the percent-change indicator next to the market
// price on /economy/{guildId}. Lives in its own module so the page IIFE
// stays focused on chart wiring and so the math can be unit-tested
// under jsdom (see web/src/test/js/economy-change.test.js).
//
// Browser callers reach this via window.TobyEconomyChange; tests pull
// it in with require().

(function (root) {
    'use strict';

    function computeChangePct(points) {
        if (!points || points.length < 2) return null;
        const first = points[0].price;
        const last = points[points.length - 1].price;
        if (!first) return null;
        return ((last - first) / first) * 100;
    }

    function formatChangeText(pct, label) {
        if (pct === null || pct === undefined || Number.isNaN(pct)) {
            return 'no data yet';
        }
        const sign = pct >= 0 ? '+' : '';
        return sign + pct.toFixed(2) + '% (' + label + ')';
    }

    function applyChange(el, points, label) {
        if (!el) return;
        const pct = computeChangePct(points);
        el.classList.remove('up', 'down');
        if (pct === null) {
            el.textContent = 'no data yet';
            return;
        }
        el.classList.add(pct >= 0 ? 'up' : 'down');
        el.textContent = formatChangeText(pct, label);
    }

    const api = {
        computeChangePct: computeChangePct,
        formatChangeText: formatChangeText,
        applyChange: applyChange,
    };

    if (root) root.TobyEconomyChange = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
