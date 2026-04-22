// Encounter Library behaviors on the campaign detail page:
//   1. Drag-and-drop (native HTML5) reordering of entry rows per encounter,
//      persisted via a JSON POST to /entries/reorder.
//   2. Alt+ArrowUp / Alt+ArrowDown on the drag handle as a keyboard
//      accessible equivalent.
//   3. The add-entry form toggles between "from library" and "ad-hoc"
//      input groups without full-page round-trips.
//   4. Both the per-card "Load into composer" button and the composer's
//      "Load encounter" select fetch the encounter JSON and populate the
//      initiative composer inputs so the DM can tweak before rolling.
//
// Mirrors the drag-drop + apiPostJson pattern from intros.js.

function initEncounterLibrary() {
    const root = document.querySelector('.encounter-library');
    const composer = document.getElementById('initiative-composer-form');
    if (!root && !composer) return;

    const guildIdAttr =
        document.body.dataset.guildId ||
        document.querySelector('[data-guild-id]')?.dataset.guildId;
    const guildId = guildIdAttr || inferGuildIdFromUrl();

    const csrfToken =
        document.querySelector('meta[name="_csrf"]')?.content || '';
    const csrfHeader =
        document.querySelector('meta[name="_csrf_header"]')?.content ||
        'X-CSRF-TOKEN';

    if (root) {
        bindAddEntryMode(root);
        bindDragReorder(root, guildId, csrfToken, csrfHeader);
        bindLoadButtons(root);
    }
    if (composer) {
        bindEncounterLoaderSelect(composer);
    }

    // --- Add-entry form: toggle library vs ad-hoc inputs ----------------
    function bindAddEntryMode(scope) {
        scope.querySelectorAll('.add-entry-form').forEach(form => {
            const mode = form.querySelectorAll('input[name="__mode"]');
            const libraryInputs = [
                form.querySelector('select[name="monsterTemplateId"]'),
                form.querySelector('input[name="quantity"]')
            ];
            const adhocInputs = form.querySelectorAll('.entry-adhoc-input');
            const apply = () => {
                const value = form.querySelector('input[name="__mode"]:checked')?.value || 'template';
                const isTemplate = value === 'template';
                libraryInputs.forEach(i => { if (i) i.style.display = isTemplate ? '' : 'none'; });
                adhocInputs.forEach(i => { i.style.display = isTemplate ? 'none' : ''; });
                // Required flips with mode so browser validation matches the visible state.
                const select = form.querySelector('select[name="monsterTemplateId"]');
                if (select) select.required = isTemplate;
                const adhocName = form.querySelector('input[name="adhocName"]');
                if (adhocName) adhocName.required = !isTemplate;
            };
            mode.forEach(r => r.addEventListener('change', apply));
            apply();
        });
    }

    // --- Drag-and-drop reorder per encounter tbody ----------------------
    function bindDragReorder(scope, guildId, csrfToken, csrfHeader) {
        scope.querySelectorAll('tbody.encounter-entry-tbody').forEach(tbody => {
            const encounterId = tbody.dataset.encounterId;
            let dragged = null;

            const rows = tbody.querySelectorAll('tr.encounter-entry-row');
            rows.forEach(row => {
                row.addEventListener('dragstart', e => {
                    dragged = row;
                    row.classList.add('dragging');
                    e.dataTransfer.effectAllowed = 'move';
                    e.dataTransfer.setData('text/plain', row.dataset.entryId || '');
                });
                row.addEventListener('dragend', () => {
                    row.classList.remove('dragging');
                    tbody.querySelectorAll('tr').forEach(r => r.classList.remove('drag-over'));
                    dragged = null;
                });
                row.addEventListener('dragover', e => {
                    e.preventDefault();
                    if (dragged && dragged !== row) row.classList.add('drag-over');
                });
                row.addEventListener('dragleave', () => row.classList.remove('drag-over'));
                row.addEventListener('drop', e => {
                    e.preventDefault();
                    row.classList.remove('drag-over');
                    if (!dragged || dragged === row) return;
                    const rect = row.getBoundingClientRect();
                    const before = (e.clientY - rect.top) < rect.height / 2;
                    tbody.insertBefore(dragged, before ? row : row.nextSibling);
                    saveOrder(tbody, encounterId);
                });

                // Keyboard-accessible reorder via the drag handle.
                const handle = row.querySelector('.drag-handle');
                if (handle) {
                    handle.addEventListener('keydown', e => {
                        if (!e.altKey) return;
                        if (e.key === 'ArrowUp' && row.previousElementSibling) {
                            tbody.insertBefore(row, row.previousElementSibling);
                            saveOrder(tbody, encounterId);
                            handle.focus();
                            e.preventDefault();
                        } else if (e.key === 'ArrowDown' && row.nextElementSibling) {
                            tbody.insertBefore(row.nextElementSibling, row);
                            saveOrder(tbody, encounterId);
                            handle.focus();
                            e.preventDefault();
                        }
                    });
                }
            });
        });

        function saveOrder(tbody, encounterId) {
            const orderedIds = Array.from(tbody.querySelectorAll('tr.encounter-entry-row'))
                .map(r => Number(r.dataset.entryId))
                .filter(Number.isFinite);
            const url = `/dnd/campaign/${guildId}/encounters/${encounterId}/entries/reorder`;
            apiPostJson(url, { orderedIds: orderedIds })
                .then(r => {
                    if (r && r.ok) {
                        toast('Order saved.', 'success', 1800);
                    } else {
                        const msg = (r && r.error) || 'Reorder failed. Reloading.';
                        toast(msg, 'error');
                        setTimeout(() => location.reload(), 800);
                    }
                })
                .catch(() => {
                    toast('Network error. Reloading.', 'error');
                    setTimeout(() => location.reload(), 800);
                });
        }
    }

    // --- Load-into-composer buttons on each encounter card --------------
    function bindLoadButtons(scope) {
        scope.querySelectorAll('.btn-load-encounter').forEach(btn => {
            btn.addEventListener('click', () => {
                const id = btn.dataset.encounterId;
                if (id) loadEncounter(id);
            });
        });
    }

    // --- Composer "Load encounter" select -------------------------------
    function bindEncounterLoaderSelect(composer) {
        const select = document.getElementById('encounterLoader');
        if (!select) return;
        select.addEventListener('change', e => {
            const value = e.target.value;
            if (value) loadEncounter(value);
        });
    }

    // --- Populate composer from an encounter id -------------------------
    function loadEncounter(encounterId) {
        fetch(`/dnd/encounters/${encounterId}`, { credentials: 'same-origin' })
            .then(r => r.ok ? r.json() : null)
            .then(enc => {
                if (!enc) {
                    toast('Could not load that encounter.', 'error');
                    return;
                }
                populateComposer(enc);
                toast(`Loaded "${enc.name}".`, 'success', 2200);
                composer?.scrollIntoView({ behavior: 'smooth', block: 'start' });
            })
            .catch(() => toast('Network error loading encounter.', 'error'));
    }

    function populateComposer(enc) {
        if (!composer) return;

        // Zero out all template quantities so we start from a clean slate.
        composer.querySelectorAll('input[name="templateQty"]').forEach(i => { i.value = '0'; });

        // Collect current ad-hoc rows — we'll clear and rewrite their values.
        const adhocRows = composer.querySelectorAll('.adhoc-rows .row');
        adhocRows.forEach(row => {
            const nameInput = row.querySelector('input[name="adhocName"]');
            const modInput = row.querySelector('input[name="adhocMod"]');
            const hpInput = row.querySelector('input[name="adhocHpExpr"]');
            const acInput = row.querySelector('input[name="adhocAc"]');
            if (nameInput) nameInput.value = '';
            if (modInput) modInput.value = '0';
            if (hpInput) hpInput.value = '';
            if (acInput) acInput.value = '';
        });

        const templateRows = composer.querySelectorAll('.template-picker .picker-row');
        const templateRowsById = {};
        templateRows.forEach(row => {
            const idInput = row.querySelector('input[name="templateId"]');
            if (idInput) templateRowsById[idInput.value] = row;
        });

        let adhocIndex = 0;
        (enc.entries || []).forEach(entry => {
            if (entry.monsterTemplate && entry.monsterTemplate.id != null) {
                const row = templateRowsById[String(entry.monsterTemplate.id)];
                if (row) {
                    const qtyInput = row.querySelector('input[name="templateQty"]');
                    if (qtyInput) qtyInput.value = String(entry.quantity || 1);
                }
            } else if (entry.adhocName) {
                const row = adhocRows[adhocIndex];
                if (row) {
                    row.querySelector('input[name="adhocName"]').value = entry.adhocName;
                    row.querySelector('input[name="adhocMod"]').value =
                        String(entry.adhocInitiativeModifier || 0);
                    if (entry.adhocHpExpression) {
                        row.querySelector('input[name="adhocHpExpr"]').value = entry.adhocHpExpression;
                    }
                    if (entry.adhocAc != null) {
                        row.querySelector('input[name="adhocAc"]').value = String(entry.adhocAc);
                    }
                    adhocIndex += 1;
                }
                // If the encounter has more ad-hoc entries than the composer
                // has rows, the extras are silently dropped. The DM can re-add
                // them manually; keeping the composer schema fixed avoids
                // ballooning the page with arbitrary row counts.
            }
        });
    }

    // --- Helpers -------------------------------------------------------

    function apiPostJson(url, body) {
        const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
        if (csrfToken) headers[csrfHeader] = csrfToken;
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers,
            body: JSON.stringify(body)
        }).then(r => r.json().catch(() => ({ ok: r.ok, error: r.ok ? null : 'Request failed.' })));
    }

    function toast(message, type, duration) {
        if (window.TobyToast && typeof window.TobyToast.show === 'function') {
            window.TobyToast.show(message, { type: type || 'info', duration: duration || 3000 });
        }
    }

    function inferGuildIdFromUrl() {
        const m = window.location.pathname.match(/\/dnd\/campaign\/(\d+)/);
        return m ? m[1] : '';
    }
}

if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initEncounterLibrary);
    } else {
        initEncounterLibrary();
    }
}
