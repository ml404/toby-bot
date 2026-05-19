// Watches panel for the market page — companion to the `/pricealert`
// Discord command. Lists the signed-in user's price triggers, lets them
// add/remove. The Discord command and this page write to the same
// `user_price_trigger` table; the scheduler doesn't care which surface
// created the row.
(function () {
    'use strict';

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const Api = window.TobyApi;
    if (!Api) return;

    const form = document.getElementById('economy-watch-form');
    const priceInput = document.getElementById('economy-watch-price');
    const sideSelect = document.getElementById('economy-watch-side');
    const amountInput = document.getElementById('economy-watch-amount');
    const submitBtn = document.getElementById('economy-watch-submit');
    const listEl = document.getElementById('economy-watch-list');
    const emptyEl = document.getElementById('economy-watch-empty');
    if (!form || !listEl) return;

    function toastError(msg) {
        if (window.toast) window.toast(msg, 'error');
    }

    function toastOk(msg) {
        if (window.toast) window.toast(msg, 'success');
    }

    function fmtPrice(p) {
        return Number(p).toFixed(4);
    }

    function statusFor(watch, currentPrice) {
        if (!watch.enabled && watch.firedAt) {
            const d = new Date(watch.firedAt);
            return 'fired ' + d.toLocaleString();
        }
        if (!watch.enabled) return 'disabled';
        // "would fire on next tick" hint when the current price has already
        // crossed the threshold from the side it started on.
        if (currentPrice != null) {
            const created = watch.priceAtCreation;
            const t = watch.threshold;
            if ((created - t) * (currentPrice - t) <= 0) return 'armed · would fire now';
        }
        return 'armed';
    }

    function renderWatches(watches, currentPrice) {
        listEl.innerHTML = '';
        if (!watches || watches.length === 0) {
            emptyEl.hidden = false;
            return;
        }
        emptyEl.hidden = true;
        watches.forEach(function (w) {
            const li = document.createElement('li');
            li.className = 'economy-watch-row';
            li.dataset.watchId = String(w.id);

            const arrow = w.threshold < w.priceAtCreation ? '↓' : '↑';
            const summary = document.createElement('div');
            summary.className = 'economy-watch-summary';
            summary.textContent =
                '#' + w.id + ' · ' + w.side + ' ' + w.amount + ' ' +
                arrow + ' ' + fmtPrice(w.threshold) +
                ' (created at ' + fmtPrice(w.priceAtCreation) + ')';

            const status = document.createElement('span');
            status.className = 'economy-watch-status muted';
            status.textContent = statusFor(w, currentPrice);

            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn-ghost economy-watch-remove';
            remove.textContent = 'Remove';
            remove.dataset.watchId = String(w.id);

            li.appendChild(summary);
            li.appendChild(status);
            li.appendChild(remove);
            listEl.appendChild(li);
        });
    }

    function loadAndRender() {
        return fetch('/economy/' + guildId + '/watches', {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }).then(function (r) {
            return r.json().catch(function () {
                return { ok: false, error: 'Request failed.' };
            });
        }).then(function (r) {
            if (!r || r.ok !== true) {
                toastError(r && r.error ? r.error : 'Failed to load watches.');
                return;
            }
            renderWatches(r.watches || [], typeof r.price === 'number' ? r.price : null);
        });
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        const threshold = parseFloat(priceInput.value);
        const side = sideSelect.value;
        const amount = parseInt(amountInput.value, 10);
        if (!isFinite(threshold) || threshold <= 0) {
            toastError('Enter a valid target price.');
            return;
        }
        if (!isFinite(amount) || amount <= 0) {
            toastError('Enter a positive amount.');
            return;
        }
        submitBtn.disabled = true;
        Api.postJson('/economy/' + guildId + '/watches', {
            threshold: threshold,
            side: side,
            amount: amount
        }).then(function (r) {
            if (!r || r.ok !== true) {
                toastError(r && r.error ? r.error : 'Failed to create watch.');
                return;
            }
            toastOk('Watch created.');
            if (r.notificationsAutoEnabled) {
                toastOk('PRICE_ALERT DMs were off; I enabled them so the receipt can reach you.');
            }
            priceInput.value = '';
            amountInput.value = '';
            return loadAndRender();
        }).finally(function () {
            submitBtn.disabled = false;
        });
    });

    listEl.addEventListener('click', function (event) {
        const target = event.target;
        if (!(target instanceof HTMLElement)) return;
        const watchId = target.dataset.watchId;
        if (!watchId || !target.classList.contains('economy-watch-remove')) return;
        target.disabled = true;
        Api.del('/economy/' + guildId + '/watches/' + watchId).then(function (r) {
            if (!r || r.ok !== true) {
                target.disabled = false;
                toastError(r && r.error ? r.error : 'Failed to remove watch.');
                return;
            }
            toastOk('Watch removed.');
            return loadAndRender();
        });
    });

    loadAndRender();
})();
