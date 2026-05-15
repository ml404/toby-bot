// Excuses page interactivity — spin, approve, delete, char-counter.
// Mutating actions go through TobyApi.postJson (CSRF-aware); the spin
// endpoint is a GET so it uses plain fetch.

(function () {
    'use strict';

    function $(sel, el) { return (el || document).querySelector(sel); }

    // ---- Char counter on the maker textarea ----
    const textarea = $('#excuseText');
    const counter = $('#charCount');
    if (textarea && counter) {
        const max = parseInt(textarea.getAttribute('maxlength') || '200', 10);
        function refresh() {
            const len = textarea.value.length;
            counter.textContent = String(len);
            counter.parentElement.classList.toggle('over', len > max);
        }
        textarea.addEventListener('input', refresh);
        refresh();
    }

    // ---- Spin random excuse ----
    const spinBtn = $('#spinBtn');
    if (spinBtn) {
        const result = $('#randomResult');
        const textEl = $('#randomText');
        const authorEl = $('#randomAuthor');
        const idEl = $('#randomId');
        spinBtn.addEventListener('click', function () {
            const url = spinBtn.dataset.randomUrl;
            if (!url) return;
            spinBtn.classList.add('spinning');
            fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
                .then(r => r.json())
                .then(json => {
                    if (json && json.ok) {
                        textEl.textContent = json.text || '';
                        authorEl.textContent = json.author || '';
                        idEl.textContent = (json.id != null) ? String(json.id) : '';
                        result.hidden = false;
                    } else {
                        const msg = (json && json.error) || 'No excuses available.';
                        if (window.TobyToasts) window.TobyToasts.info(msg);
                        result.hidden = true;
                    }
                })
                .catch(() => {
                    if (window.TobyToasts) window.TobyToasts.error('Could not fetch an excuse.');
                })
                .finally(() => spinBtn.classList.remove('spinning'));
        });
    }

    // ---- Approve / Delete buttons on each row ----
    document.addEventListener('click', function (ev) {
        const btn = ev.target.closest('button[data-action]');
        if (!btn) return;
        const action = btn.dataset.action;
        const url = btn.dataset.url;
        if (!url) return;

        if (action === 'delete') {
            const confirmed = window.confirm('Delete this excuse?');
            if (!confirmed) return;
        }

        btn.disabled = true;
        const promise = window.TobyApi
            ? window.TobyApi.postJson(url, {})
            : fetch(url, { method: 'POST', credentials: 'same-origin' }).then(r => r.json());

        promise.then(json => {
            if (json && json.ok) {
                const card = btn.closest('.excuse-card');
                if (action === 'delete' && card) {
                    card.classList.add('removing');
                    setTimeout(() => card.remove(), 250);
                    if (window.TobyToasts) window.TobyToasts.success('Deleted.');
                } else if (action === 'approve' && card) {
                    if (window.TobyToasts) window.TobyToasts.success('Approved.');
                    // The card's state (pending vs approved) depends on the
                    // current tab, so reload the page rather than mutate
                    // in-place — the approved version may need to move tabs.
                    setTimeout(() => window.location.reload(), 400);
                }
            } else {
                const msg = (json && json.error) || 'That action failed.';
                if (window.TobyToasts) window.TobyToasts.error(msg);
                btn.disabled = false;
            }
        }).catch(() => {
            if (window.TobyToasts) window.TobyToasts.error('Request failed.');
            btn.disabled = false;
        });
    });

    // ---- Replay flash messages as toasts (mirrors intros.js behavior) ----
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('[data-flash]').forEach(function (node) {
            const kind = node.dataset.flash;
            const msg = node.textContent.trim();
            if (!msg || !window.TobyToasts) return;
            if (kind === 'success') window.TobyToasts.success(msg);
            else if (kind === 'error') window.TobyToasts.error(msg);
            else window.TobyToasts.info(msg);
        });
    });
})();
