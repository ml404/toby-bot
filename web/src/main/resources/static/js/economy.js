(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const postJson = window.TobyApi.postJson;

    function toast(msg, type) {
        if (window.TobyToast && typeof window.TobyToast.show === 'function') {
            window.TobyToast.show(msg, { type: type || 'info' });
        } else {
            console.log('[' + (type || 'info') + '] ' + msg);
        }
    }

    // -- Chart --------------------------------------------------------------
    const canvas = document.getElementById('economy-chart');
    const empty = document.getElementById('economy-chart-empty');
    const windowButtons = document.querySelectorAll('#economy-windows button');
    const recentTradesPanel = document.getElementById('economy-recent-trades-panel');
    const recentTradesList = document.getElementById('economy-recent-trades');
    const priceChangeEl = document.querySelector('.economy-price-change');
    // Cap is generous — the panel scrolls (see economy.css), so the
    // only ceiling is rendering cost on very busy markets.
    const RECENT_TRADES_LIMIT = 100;

    const BUY_COLOR = '#57F287';
    const SELL_COLOR = '#ED4245';

    let chart = null;
    let currentWindow = '1d';

    function tradeMarkerData(trades) {
        return trades.map(function (t) {
            return {
                x: t.t,
                y: t.price,
                trade: t
            };
        });
    }

    function tradeMarkerColors(trades) {
        return trades.map(function (t) {
            return t.side === 'BUY' ? BUY_COLOR : SELL_COLOR;
        });
    }

    function tradeMarkerStyles(trades) {
        return trades.map(function (t) {
            return t.side === 'BUY' ? 'triangle' : 'rectRot';
        });
    }

    function tradeMarkerDataset(trades) {
        return {
            label: 'Trades',
            type: 'scatter',
            data: tradeMarkerData(trades),
            pointBackgroundColor: tradeMarkerColors(trades),
            pointBorderColor: tradeMarkerColors(trades),
            pointStyle: tradeMarkerStyles(trades),
            pointRadius: 6,
            pointHoverRadius: 8,
            showLine: false,
            order: 0
        };
    }

    function formatRelative(ms) {
        const diff = Math.max(0, Date.now() - ms);
        const mins = Math.floor(diff / 60000);
        if (mins < 1) return 'just now';
        if (mins < 60) return mins + 'm ago';
        const hours = Math.floor(mins / 60);
        if (hours < 24) return hours + 'h ago';
        return Math.floor(hours / 24) + 'd ago';
    }

    // Map of reason discriminator → human-readable suffix shown next to
    // a trade. USER trades render no suffix; the others get a tag so the
    // viewer can tell organic activity from automated tops-up.
    function reasonSuffix(reason) {
        switch (reason) {
            case 'TITLE_TOPUP': return ' (title top-up)';
            case 'CASINO_TOPUP': return ' (casino top-up)';
            default: return '';
        }
    }

    function renderRecentTrades(trades) {
        if (!recentTradesList || !recentTradesPanel) return;
        if (!trades.length) {
            recentTradesPanel.hidden = true;
            return;
        }
        recentTradesPanel.hidden = false;
        // Newest first, capped — older activity stays on the chart but the
        // textual log is bounded so it doesn't grow without limit on busy markets.
        const recent = trades.slice().reverse().slice(0, RECENT_TRADES_LIMIT);
        recentTradesList.innerHTML = '';
        recent.forEach(function (t) {
            const li = document.createElement('li');
            li.className = 'economy-trade-row';
            const verb = t.side === 'BUY' ? 'bought' : 'sold';
            const verbClass = t.side === 'BUY' ? 'economy-trade-buy' : 'economy-trade-sell';
            const suffix = reasonSuffix(t.reason);
            li.innerHTML =
                '<span class="economy-trade-when">' + formatRelative(t.t) + '</span>' +
                '<span class="economy-trade-who">' + escapeHtml(t.name) + '</span>' +
                ' <span class="' + verbClass + '">' + verb + '</span> ' +
                '<strong>' + t.amount + '</strong> @ ' +
                t.price.toFixed(2) +
                (suffix ? '<span class="economy-trade-reason">' + escapeHtml(suffix) + '</span>' : '');
            recentTradesList.appendChild(li);
        });
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    function chartConfig(points, trades) {
        const data = points.map(function (p) { return { x: p.t, y: p.price }; });
        return {
            type: 'line',
            data: {
                datasets: [
                    {
                        label: 'TOBY',
                        data: data,
                        borderColor: '#57F287',
                        backgroundColor: 'rgba(87, 242, 135, 0.15)',
                        pointRadius: 0,
                        tension: 0.25,
                        fill: true,
                        borderWidth: 2,
                        order: 1
                    },
                    tradeMarkerDataset(trades)
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'nearest', intersect: false },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        backgroundColor: '#16213e',
                        borderColor: '#3a3a5a',
                        borderWidth: 1,
                        titleColor: '#e0e0e0',
                        bodyColor: '#e0e0e0',
                        callbacks: {
                            title: function (items) {
                                return items.length ? new Date(items[0].parsed.x).toLocaleString() : '';
                            },
                            label: function (item) {
                                const trade = item.raw && item.raw.trade;
                                if (trade) {
                                    const verb = trade.side === 'BUY' ? 'bought' : 'sold';
                                    return trade.name + ' ' + verb + ' ' + trade.amount + ' @ ' +
                                        trade.price.toFixed(2) + reasonSuffix(trade.reason);
                                }
                                return item.parsed.y.toFixed(2) + ' credits';
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        type: 'linear',
                        ticks: {
                            color: '#a0a0b0',
                            callback: function (v) {
                                const d = new Date(v);
                                return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
                            },
                            maxRotation: 0,
                            autoSkipPadding: 20
                        },
                        grid: { color: '#2a2a4a' }
                    },
                    y: {
                        ticks: {
                            color: '#a0a0b0',
                            callback: function (v) { return Number(v).toFixed(2); }
                        },
                        grid: { color: '#2a2a4a' }
                    }
                }
            }
        };
    }

    function activeWindowLabel(windowCode) {
        // Reuse the button text so the indicator stays in sync with the
        // segmented control (1D / 5D / 1M / 3M / 1Y / ALL) without keeping
        // a parallel label table around.
        for (let i = 0; i < windowButtons.length; i++) {
            if (windowButtons[i].dataset.window === windowCode) {
                return (windowButtons[i].textContent || windowCode).trim();
            }
        }
        return windowCode;
    }

    function loadHistory(windowCode) {
        currentWindow = windowCode;
        windowButtons.forEach(function (b) {
            b.setAttribute('aria-pressed', b.dataset.window === windowCode ? 'true' : 'false');
        });
        fetch('/economy/' + guildId + '/history?window=' + encodeURIComponent(windowCode), {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }).then(function (r) { return r.json(); })
          .then(function (body) {
            const points = (body && body.points) || [];
            const trades = (body && body.trades) || [];
            renderRecentTrades(trades);
            if (window.TobyEconomyChange) {
                window.TobyEconomyChange.applyChange(priceChangeEl, points, activeWindowLabel(windowCode));
            }
            if (points.length < 2) {
                if (chart) { chart.destroy(); chart = null; }
                if (empty) empty.hidden = false;
                return;
            }
            if (empty) empty.hidden = true;
            if (chart) {
                chart.data.datasets[0].data = points.map(function (p) { return { x: p.t, y: p.price }; });
                const markerDs = chart.data.datasets[1];
                markerDs.data = tradeMarkerData(trades);
                markerDs.pointBackgroundColor = tradeMarkerColors(trades);
                markerDs.pointBorderColor = tradeMarkerColors(trades);
                markerDs.pointStyle = tradeMarkerStyles(trades);
                chart.update('none');
            } else {
                chart = new Chart(canvas.getContext('2d'), chartConfig(points, trades));
            }
          })
          .catch(function () { toast('Could not load chart.', 'error'); });
    }

    windowButtons.forEach(function (btn) {
        btn.addEventListener('click', function () { loadHistory(btn.dataset.window); });
    });

    loadHistory(currentWindow);
    // Gentle live-update every 30 s so the chart breathes without hammering the server.
    setInterval(function () { loadHistory(currentWindow); }, 30000);

    // -- Trade --------------------------------------------------------------
    const amountInput = document.getElementById('economy-amount');
    const buyBtn = document.getElementById('economy-buy');
    const sellBtn = document.getElementById('economy-sell');
    const coinsEl = document.getElementById('economy-coins');
    const creditsEl = document.getElementById('economy-credits');
    const portfolioEl = document.getElementById('economy-portfolio');
    const priceEl = document.getElementById('economy-price');

    function applyTradeResult(r) {
        if (r.newCoins !== null && coinsEl) coinsEl.textContent = r.newCoins + ' TOBY';
        if (r.newCredits !== null && creditsEl) creditsEl.textContent = r.newCredits + ' credits';
        if (r.newPrice !== null && priceEl) priceEl.textContent = r.newPrice.toFixed(2);
        if (r.newCoins !== null && r.newPrice !== null && portfolioEl) {
            portfolioEl.textContent = Math.floor(r.newCoins * r.newPrice) + ' credits';
        }
    }

    function trade(kind, btn) {
        const amount = parseInt(amountInput.value, 10);
        if (!amount || amount <= 0) { toast('Enter a positive amount.', 'error'); return; }
        btn.disabled = true;
        postJson('/economy/' + guildId + '/' + kind, { amount: amount })
            .then(function (r) {
                btn.disabled = false;
                if (r && r.ok) {
                    toast(kind === 'buy' ? 'Bought ' + amount + ' TOBY.' : 'Sold ' + amount + ' TOBY.', 'success');
                    applyTradeResult(r);
                    loadHistory(currentWindow);
                } else {
                    toast((r && r.error) || 'Trade failed.', 'error');
                }
            })
            .catch(function () { btn.disabled = false; toast('Network error.', 'error'); });
    }

    if (buyBtn) buyBtn.addEventListener('click', function () { trade('buy', buyBtn); });
    if (sellBtn) sellBtn.addEventListener('click', function () { trade('sell', sellBtn); });
})();
