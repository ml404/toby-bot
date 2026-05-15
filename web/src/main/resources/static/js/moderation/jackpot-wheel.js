// Per-tier editor for the `JACKPOT_WHEEL_SEGMENTS` config row on the
// moderation/settings page. The config value is a CSV
// (`80:1,10:5,5:10,4:20,1:50`) so a single `<input type="text">` would
// be unforgiving; this widget renders one (weight, payout %) pair per
// segment with add/remove buttons, a live EV preview, and client-side
// validation that mirrors what `JackpotWheel.validateConfigString`
// does on the server. Server-side validation is the source of truth —
// these checks just keep the UX honest.
(function () {
    'use strict';

    const DEFAULT = '80:1,10:5,5:10,4:20,1:50';
    const MAX_SEGMENTS = 12;

    function parseCsv(csv) {
        if (!csv || typeof csv !== 'string') return [];
        const out = [];
        csv.split(',').forEach(pair => {
            const trimmed = pair.trim();
            if (!trimmed) return;
            const parts = trimmed.split(':').map(p => p.trim());
            if (parts.length !== 2) return;
            const w = parseInt(parts[0], 10);
            const p = parseInt(parts[1], 10);
            if (!Number.isFinite(w) || !Number.isFinite(p)) return;
            out.push({ weight: w, pct: p });
        });
        return out;
    }

    function serializeCsv(rows) {
        return rows.map(r => r.weight + ':' + r.pct).join(',');
    }

    function validate(rows) {
        if (rows.length === 0) return 'At least one segment required.';
        if (rows.length > MAX_SEGMENTS) return 'At most ' + MAX_SEGMENTS + ' segments.';
        let total = 0;
        for (let i = 0; i < rows.length; i++) {
            const r = rows[i];
            if (!Number.isFinite(r.weight) || r.weight <= 0) {
                return 'Segment ' + (i + 1) + ' weight must be a positive integer.';
            }
            if (!Number.isFinite(r.pct) || r.pct < 1 || r.pct > 100) {
                return 'Segment ' + (i + 1) + ' payout % must be 1-100.';
            }
            total += r.weight;
        }
        if (total <= 0) return 'Total weight must be > 0.';
        return null;
    }

    function expectedValuePct(rows) {
        const total = rows.reduce((s, r) => s + r.weight, 0);
        if (total <= 0) return 0;
        return rows.reduce((s, r) => s + (r.weight / total) * r.pct, 0);
    }

    function buildEditor(editor) {
        const rowsContainer = editor.querySelector('.jackpot-wheel-editor-rows');
        const addBtn = editor.querySelector('.jackpot-wheel-add');
        const evEl = editor.querySelector('.jackpot-wheel-ev');
        const errEl = editor.querySelector('.jackpot-wheel-error');
        const saveBtn = editor.parentElement.querySelector('.jackpot-wheel-save');
        if (!rowsContainer || !addBtn || !saveBtn) return null;

        // Seed from data-current; empty/null falls back to DEFAULT so
        // the admin sees what would happen if they hit save with no
        // edits (the default is also what the server uses when the row
        // is unset).
        let current = editor.dataset.current || '';
        if (!current || current === 'null') current = DEFAULT;
        let state = parseCsv(current);
        if (state.length === 0) state = parseCsv(DEFAULT);

        function render() {
            rowsContainer.innerHTML = '';
            state.forEach((row, idx) => {
                const div = document.createElement('div');
                div.className = 'jackpot-wheel-editor-row';
                div.innerHTML =
                    '<span class="jackpot-wheel-row-label">' + (idx + 1) + '</span>' +
                    '<label>Weight <input type="number" min="1" step="1" class="jw-weight" value="' + row.weight + '"></label>' +
                    '<label>Payout <input type="number" min="1" max="100" step="1" class="jw-pct" value="' + row.pct + '"><span class="config-suffix">%</span></label>' +
                    '<button type="button" class="btn btn-sm jw-remove" aria-label="Remove segment">×</button>';
                div.querySelector('.jw-weight').addEventListener('input', e => {
                    state[idx].weight = parseInt(e.target.value, 10);
                    refresh();
                });
                div.querySelector('.jw-pct').addEventListener('input', e => {
                    state[idx].pct = parseInt(e.target.value, 10);
                    refresh();
                });
                div.querySelector('.jw-remove').addEventListener('click', () => {
                    if (state.length <= 1) return;
                    state.splice(idx, 1);
                    render();
                    refresh();
                });
                rowsContainer.appendChild(div);
            });
            addBtn.disabled = state.length >= MAX_SEGMENTS;
        }

        function refresh() {
            const err = validate(state);
            if (err) {
                errEl.hidden = false;
                errEl.textContent = err;
                evEl.textContent = '';
            } else {
                errEl.hidden = true;
                errEl.textContent = '';
                evEl.textContent = 'Expected payout per win: ' + expectedValuePct(state).toFixed(2) + '% of pool';
            }
        }

        addBtn.addEventListener('click', () => {
            if (state.length >= MAX_SEGMENTS) return;
            state.push({ weight: 1, pct: 10 });
            render();
            refresh();
        });

        saveBtn.addEventListener('click', () => {
            const err = validate(state);
            if (err) {
                if (window.ModerationCommon) {
                    // No exported toast helper — reuse the error region.
                    errEl.hidden = false;
                    errEl.textContent = err;
                }
                return;
            }
            const csv = serializeCsv(state);
            saveBtn.disabled = true;
            window.ModerationCommon.postJson(
                '/moderation/' + window.ModerationCommon.guildId + '/config',
                { key: 'JACKPOT_WHEEL_SEGMENTS', value: csv }
            ).then(r => {
                saveBtn.disabled = false;
                if (r && r.ok) {
                    editor.dataset.current = csv;
                    if (window.toast) window.toast('Wheel saved.', 'success');
                } else {
                    errEl.hidden = false;
                    errEl.textContent = (r && r.error) || 'Could not save wheel.';
                }
            }).catch(() => {
                saveBtn.disabled = false;
                errEl.hidden = false;
                errEl.textContent = 'Network error.';
            });
        });

        render();
        refresh();
        return { state: state };
    }

    document.querySelectorAll('.jackpot-wheel-editor').forEach(buildEditor);
})();
