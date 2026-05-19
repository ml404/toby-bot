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
            return { text: 'fired ' + d.toLocaleString(), cls: 'fired' };
        }
        if (!watch.enabled) return { text: 'disabled', cls: 'disabled' };
        if (currentPrice != null) {
            const created = watch.priceAtCreation;
            const t = watch.threshold;
            // "would fire on next tick" — current price already past the
            // threshold from the side it started on at creation.
            if ((created - t) * (currentPrice - t) <= 0) {
                return { text: 'would fire now', cls: 'fire-now' };
            }
        }
        return { text: 'armed', cls: '' };
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

            const main = document.createElement('div');
            main.className = 'economy-watch-main';

            const side = document.createElement('span');
            const sideCls = w.side === 'SELL' ? 'economy-watch-side-sell' : 'economy-watch-side-buy';
            side.className = 'economy-watch-side ' + sideCls;
            side.textContent = w.side + ' ' + w.amount;

            const target = document.createElement('span');
            target.className = 'economy-watch-target';
            const arrowChar = w.threshold < w.priceAtCreation ? '↓' : '↑';
            const arrow = document.createElement('span');
            arrow.className = 'economy-watch-arrow';
            arrow.textContent = arrowChar;
            arrow.setAttribute('aria-hidden', 'true');
            target.appendChild(arrow);
            target.appendChild(document.createTextNode(fmtPrice(w.threshold)));

            main.appendChild(side);
            main.appendChild(target);

            const status = statusFor(w, currentPrice);
            const statusEl = document.createElement('span');
            statusEl.className = 'economy-watch-status' + (status.cls ? ' ' + status.cls : '');
            statusEl.textContent = status.text;

            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn-ghost economy-watch-remove';
            remove.textContent = 'Remove';
            remove.dataset.watchId = String(w.id);
            remove.setAttribute('aria-label', 'Remove watch #' + w.id);

            const sub = document.createElement('div');
            sub.className = 'economy-watch-sub';
            sub.textContent = '#' + w.id + ' · created at ' + fmtPrice(w.priceAtCreation);

            li.appendChild(main);
            li.appendChild(statusEl);
            li.appendChild(remove);
            li.appendChild(sub);
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
