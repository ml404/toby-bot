// Coin-convert panel for the casino pages. Lists every coin the signed-in
// player holds (TOBY plus the volatile coins) and lets them sell any of it
// straight into social credit to fund a wager — without leaving the game.
//
// It reuses the economy sell endpoint (`POST /economy/{guildId}/sell?coin=`)
// so there's no bespoke casino-side trade path. The rendering helpers live
// on `window.TobyCoinConvert` so Jest can exercise them without the DOM
// fetch wiring; the IIFE at the bottom is the only piece that touches
// global selectors / network and is skipped when there's no game page.
(function (root) {
    'use strict';

    // Safe parse of the JSON island — a missing/empty/garbled blob yields
    // an empty portfolio rather than throwing on page load.
    function parseHoldings(text) {
        if (!text) return [];
        try {
            const parsed = JSON.parse(text);
            return Array.isArray(parsed) ? parsed.filter(function (h) {
                return h && typeof h.amount === 'number' && h.amount > 0;
            }) : [];
        } catch (e) {
            return [];
        }
    }

    // Rough credits-at-market estimate for display only — the server's sell
    // is authoritative (slippage + fee), so the panel labels it "≈".
    function estimateValue(price, amount) {
        const p = Number(price) || 0;
        const n = Number(amount) || 0;
        return Math.floor(p * n);
    }

    function buildRow(doc, holding) {
        const li = doc.createElement('li');
        li.className = 'casino-convert-row';
        li.dataset.coin = holding.symbol;

        const label = doc.createElement('div');
        label.className = 'casino-convert-coin';
        const sym = doc.createElement('span');
        sym.className = 'casino-convert-sym';
        sym.textContent = holding.symbol;
        const meta = doc.createElement('span');
        meta.className = 'casino-convert-meta muted';
        meta.textContent = holding.amount + ' · ≈ ' + estimateValue(holding.price, holding.amount) + ' cr';
        label.appendChild(sym);
        label.appendChild(meta);

        const amount = doc.createElement('input');
        amount.type = 'number';
        amount.min = '1';
        amount.step = '1';
        amount.value = String(holding.amount);
        amount.className = 'casino-convert-amount';
        amount.setAttribute('aria-label', 'Amount of ' + holding.symbol + ' to sell');

        const sell = doc.createElement('button');
        sell.type = 'button';
        sell.className = 'btn-ghost casino-convert-sell';
        sell.dataset.coin = holding.symbol;
        sell.textContent = 'Sell → credits';

        li.appendChild(label);
        li.appendChild(amount);
        li.appendChild(sell);
        return li;
    }

    // Builds (but does not attach) the whole panel for [holdings]. Returns
    // null when the player holds nothing, so callers can skip rendering.
    function renderPanel(doc, holdings) {
        if (!holdings || holdings.length === 0) return null;
        const section = doc.createElement('section');
        section.className = 'casino-convert-card';
        section.id = 'casino-convert';

        const heading = doc.createElement('h2');
        heading.className = 'casino-convert-title';
        heading.textContent = 'Cash coins → credits';
        section.appendChild(heading);

        const hint = doc.createElement('p');
        hint.className = 'casino-convert-hint muted';
        hint.textContent = 'Sell any coin you hold for social credit to top up your wager.';
        section.appendChild(hint);

        const list = doc.createElement('ul');
        list.className = 'casino-convert-list';
        holdings.forEach(function (h) { list.appendChild(buildRow(doc, h)); });
        section.appendChild(list);
        return section;
    }

    const api = {
        parseHoldings: parseHoldings,
        estimateValue: estimateValue,
        renderPanel: renderPanel,
        buildRow: buildRow,
    };
    if (root) root.TobyCoinConvert = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);

// DOM-wiring shell. Skipped under Jest (no game <main> in the test DOM).
(function () {
    'use strict';
    if (typeof window === 'undefined' || typeof document === 'undefined') return;

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;
    const Convert = window.TobyCoinConvert;
    const Api = window.TobyApi;
    if (!Convert || !Api) return;

    const island = document.getElementById('casino-coin-holdings');
    const holdings = Convert.parseHoldings(island && island.textContent);
    const panel = Convert.renderPanel(document, holdings);
    if (!panel) return;

    // One-time style injection so the panel looks right regardless of which
    // per-game stylesheet the page loaded.
    if (!document.getElementById('casino-convert-style')) {
        const style = document.createElement('style');
        style.id = 'casino-convert-style';
        style.textContent =
            '.casino-convert-card{margin:16px 0;padding:14px 16px;border:1px solid rgba(255,255,255,.12);' +
            'border-radius:12px;background:rgba(255,255,255,.03)}' +
            '.casino-convert-title{margin:0 0 2px;font-size:1rem}' +
            '.casino-convert-hint{margin:0 0 10px;font-size:.8rem}' +
            '.casino-convert-list{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:8px}' +
            '.casino-convert-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}' +
            '.casino-convert-coin{display:flex;flex-direction:column;min-width:108px}' +
            '.casino-convert-sym{font-weight:700}' +
            '.casino-convert-meta{font-size:.75rem}' +
            '.casino-convert-amount{width:96px;padding:4px 6px}' +
            '.casino-convert-row.is-empty{opacity:.5}';
        document.head.appendChild(style);
    }

    const guildId = main.dataset.guildId;
    main.insertBefore(panel, main.firstChild);

    function toast(msg, kind) { if (window.toast) window.toast(msg, kind); }

    function refreshBalance(newCredits) {
        if (typeof newCredits !== 'number') return;
        const balEl = document.querySelector('[id$="-balance"]');
        if (window.TobyBalance) window.TobyBalance.update(balEl, newCredits);
    }

    panel.addEventListener('click', function (event) {
        const target = event.target;
        if (!(target instanceof HTMLElement)) return;
        const btn = target.closest('.casino-convert-sell');
        if (!btn) return;
        const row = btn.closest('.casino-convert-row');
        if (!row) return;
        const coin = btn.dataset.coin;
        const amountInput = row.querySelector('.casino-convert-amount');
        const amount = parseInt(amountInput && amountInput.value, 10);
        if (!amount || amount <= 0) { toast('Enter a positive amount.', 'error'); return; }

        btn.disabled = true;
        Api.postJson('/economy/' + guildId + '/sell?coin=' + encodeURIComponent(coin), { amount: amount })
            .then(function (r) {
                btn.disabled = false;
                if (!r || r.ok !== true) {
                    toast((r && r.error) || 'Sell failed.', 'error');
                    return;
                }
                toast('Sold ' + amount + ' ' + coin + ' for credits.', 'success');
                refreshBalance(r.newCredits);
                const left = typeof r.newCoins === 'number' ? r.newCoins : 0;
                if (left <= 0) {
                    row.remove();
                    if (!panel.querySelector('.casino-convert-row')) panel.remove();
                } else if (amountInput) {
                    amountInput.value = String(left);
                    const meta = row.querySelector('.casino-convert-meta');
                    if (meta) meta.textContent = left + ' left';
                }
            })
            .catch(function () { btn.disabled = false; toast('Network error.', 'error'); });
    });
})();
