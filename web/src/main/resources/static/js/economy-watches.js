// Watches panel for the market page — companion to the `/pricealert`
// Discord command. Lists the signed-in user's price triggers, lets
// them add/remove. The Discord command and this page write to the
// same `user_price_trigger` table; the scheduler doesn't care which
// surface created the row.
//
// Layout: the rendering helpers live in `TobyWatches` so Jest can hit
// them via `require()` without a DOM-event wiring step. The IIFE at
// the bottom is the only piece that touches global selectors / fetch.
(function (root) {
    'use strict';

    function fmtPrice(p) {
        return Number(p).toFixed(4);
    }

    // Reads as { text, cls } so the pill can swap colour:
    //   - "would fire now" / fire-now   → the current price has already
    //     crossed the threshold from the side it was on at creation, so
    //     the next tick fires (mirrors UserPriceTriggerService.findTriggered).
    //   - armed                          → still waiting.
    //   - fired <date>                   → one-shot rows after firing.
    //   - disabled                       → manually-disabled rows.
    function statusFor(watch, currentPrice) {
        if (!watch.enabled && watch.firedAt) {
            return {
                text: 'fired ' + new Date(watch.firedAt).toLocaleString(),
                cls: 'fired',
            };
        }
        if (!watch.enabled) return { text: 'disabled', cls: 'disabled' };
        if (currentPrice != null) {
            const created = watch.priceAtCreation;
            const t = watch.threshold;
            if ((created - t) * (currentPrice - t) <= 0) {
                return { text: 'would fire now', cls: 'fire-now' };
            }
        }
        return { text: 'armed', cls: 'armed' };
    }

    // Inline SVG for the remove "✕" — gives a cleaner visual than the
    // unicode glyph and keeps colour controllable via currentColor.
    function makeRemoveIcon(doc) {
        const svg = doc.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 16 16');
        svg.setAttribute('width', '14');
        svg.setAttribute('height', '14');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        const path = doc.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute(
            'd',
            'M3.5 3.5 L12.5 12.5 M12.5 3.5 L3.5 12.5'
        );
        path.setAttribute('stroke', 'currentColor');
        path.setAttribute('stroke-width', '2');
        path.setAttribute('stroke-linecap', 'round');
        svg.appendChild(path);
        return svg;
    }

    // Builds one <li> for a watch. Pure DOM construction — no globals,
    // no fetch — so the Jest tests can assert on the rendered tree.
    function renderWatchRow(doc, watch, currentPrice) {
        const li = doc.createElement('li');
        li.className = 'economy-watch-row';
        li.dataset.watchId = String(watch.id);
        if (!watch.enabled) li.classList.add('is-inactive');

        const main = doc.createElement('div');
        main.className = 'economy-watch-main';

        const side = doc.createElement('span');
        const sideCls = watch.side === 'SELL'
            ? 'economy-watch-side-sell'
            : 'economy-watch-side-buy';
        side.className = 'economy-watch-side ' + sideCls;
        side.textContent = watch.side + ' ' + watch.amount;

        const dropping = watch.threshold < watch.priceAtCreation;
        const target = doc.createElement('span');
        target.className = 'economy-watch-target';
        const arrow = doc.createElement('span');
        arrow.className = 'economy-watch-arrow ' +
            (dropping ? 'economy-watch-arrow-down' : 'economy-watch-arrow-up');
        arrow.textContent = dropping ? '↓' : '↑';
        arrow.setAttribute('aria-hidden', 'true');
        const value = doc.createElement('span');
        value.className = 'economy-watch-target-value';
        value.textContent = fmtPrice(watch.threshold);
        target.appendChild(arrow);
        target.appendChild(value);

        const from = doc.createElement('span');
        from.className = 'economy-watch-from';
        from.textContent = 'from ' + fmtPrice(watch.priceAtCreation);

        main.appendChild(side);
        main.appendChild(target);
        main.appendChild(from);

        const status = statusFor(watch, currentPrice);
        const statusEl = doc.createElement('span');
        statusEl.className = 'economy-watch-status' +
            (status.cls ? ' status-' + status.cls : '');
        statusEl.textContent = status.text;

        const remove = doc.createElement('button');
        remove.type = 'button';
        remove.className = 'btn-icon economy-watch-remove';
        remove.dataset.watchId = String(watch.id);
        remove.setAttribute('aria-label', 'Remove watch #' + watch.id);
        remove.setAttribute('title', 'Remove');
        remove.appendChild(makeRemoveIcon(doc));
        const removeText = doc.createElement('span');
        removeText.className = 'economy-watch-remove-text';
        removeText.textContent = 'Remove';
        remove.appendChild(removeText);

        const idTag = doc.createElement('span');
        idTag.className = 'economy-watch-id';
        idTag.textContent = '#' + watch.id;

        li.appendChild(main);
        li.appendChild(statusEl);
        li.appendChild(remove);
        li.appendChild(idTag);
        return li;
    }

    // Replaces the contents of listEl with one row per watch, or shows
    // the empty-state element when watches is empty/missing. Returns
    // the rendered row count so callers can assert.
    function renderWatches(listEl, emptyEl, watches, currentPrice) {
        const doc = listEl.ownerDocument || document;
        listEl.innerHTML = '';
        if (!watches || watches.length === 0) {
            if (emptyEl) emptyEl.hidden = false;
            return 0;
        }
        if (emptyEl) emptyEl.hidden = true;
        watches.forEach(function (w) {
            listEl.appendChild(renderWatchRow(doc, w, currentPrice));
        });
        return watches.length;
    }

    const api = {
        statusFor: statusFor,
        renderWatchRow: renderWatchRow,
        renderWatches: renderWatches,
        fmtPrice: fmtPrice,
    };

    if (root) root.TobyWatches = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);

// DOM-wiring shell. Skipped under Jest because module.parent will be
// set when this file is `require()`'d from a test — saves the test
// runner from looking up selectors that don't exist in the test DOM.
(function () {
    'use strict';
    if (typeof window === 'undefined' || typeof document === 'undefined') return;

    const main = document.querySelector('main[data-guild-id]');
    if (!main) return;

    const guildId = main.dataset.guildId;
    const Api = window.TobyApi;
    const Watches = window.TobyWatches;
    if (!Api || !Watches) return;

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
            Watches.renderWatches(
                listEl, emptyEl, r.watches || [],
                typeof r.price === 'number' ? r.price : null
            );
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
        const btn = target.closest('.economy-watch-remove');
        if (!btn) return;
        const watchId = btn.dataset.watchId;
        if (!watchId) return;
        btn.disabled = true;
        Api.del('/economy/' + guildId + '/watches/' + watchId).then(function (r) {
            if (!r || r.ok !== true) {
                btn.disabled = false;
                toastError(r && r.error ? r.error : 'Failed to remove watch.');
                return;
            }
            toastOk('Watch removed.');
            return loadAndRender();
        });
    });

    loadAndRender();
})();
