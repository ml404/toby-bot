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

    let chart = null;
    let currentWindow = '1d';

    function chartConfig(points) {
        const data = points.map(function (p) { return { x: p.t, y: p.price }; });
        return {
            type: 'line',
            data: {
                datasets: [{
                    label: 'TOBY',
                    data: data,
                    borderColor: '#57F287',
                    backgroundColor: 'rgba(87, 242, 135, 0.15)',
                    pointRadius: 0,
                    tension: 0.25,
                    fill: true,
                    borderWidth: 2
                }]
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
            if (points.length < 2) {
                if (chart) { chart.destroy(); chart = null; }
                if (empty) empty.hidden = false;
                return;
            }
            if (empty) empty.hidden = true;
            if (chart) {
                chart.data.datasets[0].data = points.map(function (p) { return { x: p.t, y: p.price }; });
                chart.update('none');
            } else {
                chart = new Chart(canvas.getContext('2d'), chartConfig(points));
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
