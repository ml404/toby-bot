/* ============================================================
   Intro page interactions
   ============================================================ */

// --- Back-compat helpers (kept for existing jest tests) ---
function toggleInput(type) {
    const urlSection = document.getElementById('urlSection');
    const fileSection = document.getElementById('fileSection');
    if (urlSection) urlSection.style.display = (type === 'url') ? 'block' : 'none';
    if (fileSection) fileSection.style.display = (type === 'file') ? 'block' : 'none';
}

function togglePlay(btn) {
    const audioId = btn.dataset.audioId;
    const audio = document.getElementById(audioId);
    if (!audio) return;
    if (audio.paused) {
        document.querySelectorAll('audio').forEach(a => { a.pause(); a.currentTime = 0; });
        document.querySelectorAll('.btn-play').forEach(b => { b.textContent = '▶'; b.classList.remove('playing'); });
        // Apply saved volume if present on the button
        const vol = parseInt(btn.dataset.volume || '100', 10);
        if (!isNaN(vol)) audio.volume = Math.max(0, Math.min(1, vol / 100));
        audio.play();
        btn.textContent = '⏹';
        btn.classList.add('playing');
    } else {
        audio.pause();
        audio.currentTime = 0;
        btn.textContent = '▶';
        btn.classList.remove('playing');
    }
}

// --- Page bootstrap (skipped when running under jest: no document.readyState dependent code) ---
function initIntroPage() {
    if (typeof document === 'undefined' || !document.body.dataset) return;
    if (!document.body.dataset.introPage) return;

    const cfg = {
        guildId: document.body.dataset.guildId,
        targetDiscordId: document.body.dataset.targetDiscordId || '',
        maxFileBytes: parseInt(document.body.dataset.maxFileKb || '550', 10) * 1024,
        maxDurationSeconds: parseInt(document.body.dataset.maxDurationSeconds || '15', 10),
        csrfToken: document.querySelector('meta[name="_csrf"]')?.content || '',
        csrfHeader: document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN',
    };

    // --- Render any flash messages as toasts ---
    document.querySelectorAll('[data-flash]').forEach(el => {
        const type = el.dataset.flash;
        const msg = el.textContent.trim();
        if (!msg) return;
        const opts = { type: type === 'error' ? 'error' : 'success' };
        if (el.dataset.undo === 'true') {
            opts.duration = 10000;
            opts.action = {
                label: 'Undo',
                onClick: function () {
                    const f = document.getElementById('undo-form');
                    if (f) f.submit();
                }
            };
        }
        window.TobyToast.show(msg, opts);
        el.remove();
    });

    // --- Segmented tabs (URL / File) ---
    const segButtons = document.querySelectorAll('.source-tab');
    segButtons.forEach(btn => {
        btn.addEventListener('click', function () {
            const type = btn.dataset.source;
            segButtons.forEach(b => b.setAttribute('aria-pressed', b === btn ? 'true' : 'false'));
            const urlSection = document.getElementById('urlSection');
            const fileSection = document.getElementById('fileSection');
            const inputTypeField = document.getElementById('inputType');
            if (urlSection) urlSection.style.display = (type === 'url') ? 'block' : 'none';
            if (fileSection) fileSection.style.display = (type === 'file') ? 'block' : 'none';
            if (inputTypeField) inputTypeField.value = type;
            try { localStorage.setItem('intro.lastSource', type); } catch (_) {}
        });
    });
    const saved = (() => { try { return localStorage.getItem('intro.lastSource'); } catch (_) { return null; } })();
    if (saved) {
        const match = document.querySelector('.source-tab[data-source="' + saved + '"]');
        if (match) match.click();
    }

    // --- Volume slider binding ---
    document.querySelectorAll('[data-volume-slider]').forEach(slider => {
        const valueEl = document.querySelector('[data-volume-value-for="' + slider.id + '"]');
        function sync() { if (valueEl) valueEl.textContent = slider.value + '%'; }
        slider.addEventListener('input', sync);
        sync();
    });

    // --- URL input: live YouTube preview ---
    const urlInput = document.getElementById('url');
    const urlPreview = document.getElementById('urlPreview');
    const urlError = document.getElementById('urlError');
    const submitBtn = document.getElementById('submitBtn');
    let previewState = { tooLong: false };
    let debounceTimer = null;

    function setUrlError(msg) {
        if (!urlError) return;
        urlError.textContent = msg || '';
        if (urlInput) urlInput.setAttribute('aria-invalid', msg ? 'true' : 'false');
    }

    function renderPreview(data) {
        if (!urlPreview) return;
        urlPreview.classList.remove('visible', 'error');
        if (!data || !data.thumbnailUrl) return;
        previewState.tooLong = data.durationSeconds != null && data.durationSeconds > cfg.maxDurationSeconds;
        const dur = data.durationSeconds != null ? formatDuration(data.durationSeconds) : '—';
        const title = data.title || '(Unknown title)';
        urlPreview.innerHTML =
            '<img src="' + escapeAttr(data.thumbnailUrl) + '" alt="" />' +
            '<div class="preview-body">' +
                '<div class="preview-title" title="' + escapeAttr(title) + '">' + escapeHtml(title) + '</div>' +
                '<div class="preview-meta">' +
                    '<span class="duration-badge' + (previewState.tooLong ? ' too-long' : '') + '">' + dur + '</span>' +
                    (data.videoId ? '<span>YouTube · ' + escapeHtml(data.videoId) + '</span>' : '') +
                '</div>' +
            '</div>';
        if (previewState.tooLong) urlPreview.classList.add('error');
        urlPreview.classList.add('visible');
    }

    function fetchPreview(url) {
        if (!url) { renderPreview(null); setUrlError(''); updateSubmitState(); return; }
        try { new URL(url); } catch (_) { setUrlError('Enter a valid URL.'); renderPreview(null); updateSubmitState(); return; }
        fetch('/intro/preview?url=' + encodeURIComponent(url), { headers: { 'Accept': 'application/json' } })
            .then(r => r.json())
            .then(data => {
                if (data && data.videoId) {
                    renderPreview(data);
                    setUrlError(data.error || '');
                } else {
                    renderPreview(null);
                    setUrlError('');
                    previewState.tooLong = false;
                }
                updateSubmitState();
            })
            .catch(() => {
                renderPreview(null);
                previewState.tooLong = false;
                updateSubmitState();
            });
    }

    if (urlInput) {
        urlInput.addEventListener('input', function () {
            if (debounceTimer) clearTimeout(debounceTimer);
            debounceTimer = setTimeout(function () { fetchPreview(urlInput.value.trim()); }, 400);
        });
    }

    // --- File drop zone ---
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('file');
    const fileSummary = document.getElementById('fileSummary');
    const fileError = document.getElementById('fileError');
    let fileValid = false;

    function setFileError(msg) {
        if (!fileError) return;
        fileError.textContent = msg || '';
        if (fileInput) fileInput.setAttribute('aria-invalid', msg ? 'true' : 'false');
    }

    function clearFileSummary() {
        if (fileSummary) fileSummary.innerHTML = '';
        if (dropZone) dropZone.classList.remove('has-file');
        fileValid = false;
        updateSubmitState();
    }

    function validateAndShowFile(file) {
        if (!file) { clearFileSummary(); return; }
        if (!/\.mp3$/i.test(file.name)) {
            setFileError('Only MP3 files are supported.');
            clearFileSummary();
            return;
        }
        if (file.size > cfg.maxFileBytes) {
            setFileError('File is too large (' + formatBytes(file.size) + '). Max is ' + formatBytes(cfg.maxFileBytes) + '.');
            clearFileSummary();
            return;
        }
        setFileError('');
        fileValid = true;
        if (dropZone) dropZone.classList.add('has-file');
        if (fileSummary) {
            const blobUrl = URL.createObjectURL(file);
            fileSummary.innerHTML = '';
            const row = document.createElement('div');
            row.style.width = '100%';
            const label = document.createElement('div');
            label.style.display = 'flex'; label.style.gap = '12px'; label.style.alignItems = 'center';
            const name = document.createElement('span');
            name.className = 'file-name'; name.textContent = file.name;
            const size = document.createElement('span');
            size.className = 'file-size'; size.textContent = formatBytes(file.size);
            label.appendChild(name); label.appendChild(size);
            const audio = document.createElement('audio');
            audio.controls = true;
            audio.src = blobUrl;
            audio.preload = 'metadata';
            const volSlider = document.querySelector('[data-volume-slider]');
            if (volSlider) {
                const apply = () => { audio.volume = Math.max(0, Math.min(1, parseInt(volSlider.value, 10) / 100)); };
                apply();
                volSlider.addEventListener('input', apply);
            }
            row.appendChild(label);
            row.appendChild(audio);
            fileSummary.appendChild(row);
        }
        updateSubmitState();
    }

    if (dropZone && fileInput) {
        dropZone.addEventListener('click', function (e) {
            if (e.target.tagName !== 'INPUT') fileInput.click();
        });
        dropZone.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
        });
        dropZone.addEventListener('dragover', function (e) {
            e.preventDefault();
            dropZone.classList.add('drag-active');
        });
        dropZone.addEventListener('dragleave', function () {
            dropZone.classList.remove('drag-active');
        });
        dropZone.addEventListener('drop', function (e) {
            e.preventDefault();
            dropZone.classList.remove('drag-active');
            const file = e.dataTransfer.files && e.dataTransfer.files[0];
            if (file) {
                // Re-assign to the underlying input so it submits with the form
                const dt = new DataTransfer();
                dt.items.add(file);
                fileInput.files = dt.files;
                validateAndShowFile(file);
            }
        });
        fileInput.addEventListener('change', function () {
            validateAndShowFile(fileInput.files && fileInput.files[0]);
        });
    }

    // --- Submit gating + loading state ---
    const form = document.getElementById('introForm');
    function updateSubmitState() {
        if (!submitBtn || !form) return;
        const source = form.querySelector('#inputType')?.value || 'url';
        let canSubmit = true;
        if (source === 'url') {
            const v = urlInput ? urlInput.value.trim() : '';
            canSubmit = v.length > 0 && !previewState.tooLong;
        } else if (source === 'file') {
            canSubmit = fileValid;
        }
        submitBtn.disabled = !canSubmit;
    }
    if (form) {
        form.addEventListener('submit', function () {
            submitBtn.setAttribute('aria-busy', 'true');
            submitBtn.disabled = true;
            form.setAttribute('aria-busy', 'true');
        });
    }
    updateSubmitState();

    // --- Delete modal ---
    document.querySelectorAll('[data-delete-intro]').forEach(btn => {
        btn.addEventListener('click', async function () {
            const name = btn.dataset.introName || 'this intro';
            const confirmed = await window.TobyModal.confirm({
                title: 'Delete intro?',
                body: 'Delete "' + name + '" from this server? You can undo this right after.',
                confirmLabel: 'Delete',
                confirmStyle: 'danger'
            });
            if (confirmed) {
                const form = btn.closest('form');
                if (form) form.submit();
            }
        });
    });

    // --- Inline volume edit in table ---
    document.querySelectorAll('[data-row-volume]').forEach(slider => {
        const introId = slider.dataset.introId;
        const valueEl = slider.parentElement.querySelector('.vol-value');
        let lastSaved = slider.value;
        slider.addEventListener('input', function () {
            if (valueEl) valueEl.textContent = slider.value + '%';
        });
        slider.addEventListener('change', function () {
            const value = parseInt(slider.value, 10);
            apiCall(buildUrl('/update-volume'), { introId: introId, volume: value }, {
                onSuccess: () => {
                    lastSaved = String(value);
                    window.TobyToast.show('Volume updated.', { type: 'success', duration: 2000 });
                    // Keep row-level play button in sync for volume-aware preview
                    const playBtn = document.querySelector('button.btn-play[data-audio-id="audio-' + introId + '"]');
                    if (playBtn) playBtn.dataset.volume = String(value);
                },
                onError: (r) => {
                    slider.value = lastSaved;
                    if (valueEl) valueEl.textContent = lastSaved + '%';
                    const msg = r ? (r.error || 'Failed to update volume.') : 'Network error.';
                    window.TobyToast.show(msg, { type: 'error' });
                }
            });
        });
    });

    // --- Inline name edit ---
    function bindNameEdit(cell) {
        const introId = cell.dataset.introId;
        const labelEl = cell.querySelector('.name-label');
        if (!labelEl || labelEl.dataset.bound === '1') return;
        labelEl.dataset.bound = '1';
        labelEl.title = 'Click to rename';
        labelEl.setAttribute('role', 'button');
        labelEl.setAttribute('tabindex', '0');

        function startEdit() {
            const current = labelEl.textContent;
            const input = document.createElement('input');
            input.type = 'text';
            input.className = 'name-edit-input';
            input.value = current;
            input.setAttribute('aria-label', 'Rename intro');
            labelEl.replaceWith(input);
            input.focus();
            input.select();

            let committed = false;
            function finish(newText) {
                if (committed) return;
                committed = true;
                const span = document.createElement('span');
                span.className = 'name-label';
                span.textContent = newText;
                input.replaceWith(span);
                // Re-bind against the new span
                labelEl.textContent = newText;
                bindNameEdit(cell);
            }

            function commit() {
                const value = input.value.trim();
                if (!value || value === current) { finish(current); return; }
                apiCall(buildUrl('/update-name'), { introId: introId, name: value }, {
                    onSuccess: () => {
                        finish(value);
                        window.TobyToast.show('Renamed.', { type: 'success', duration: 2000 });
                    },
                    onError: (r) => {
                        finish(current);
                        const msg = r ? (r.error || 'Rename failed.') : 'Network error.';
                        window.TobyToast.show(msg, { type: 'error' });
                    }
                });
            }

            input.addEventListener('blur', commit);
            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); commit(); }
                if (e.key === 'Escape') { e.preventDefault(); finish(current); }
            });
        }

        labelEl.addEventListener('click', startEdit);
        labelEl.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); startEdit(); }
        });
    }
    document.querySelectorAll('[data-editable-name]').forEach(bindNameEdit);

    // --- Drag reorder ---
    const tbody = document.getElementById('introsTbody');
    if (tbody) {
        let draggedRow = null;
        tbody.querySelectorAll('tr[data-intro-id]').forEach(row => {
            row.setAttribute('draggable', 'true');
            row.addEventListener('dragstart', function (e) {
                draggedRow = row;
                row.classList.add('dragging');
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', row.dataset.introId);
            });
            row.addEventListener('dragend', function () {
                row.classList.remove('dragging');
                tbody.querySelectorAll('tr').forEach(r => r.classList.remove('drag-over'));
            });
            row.addEventListener('dragover', function (e) {
                e.preventDefault();
                if (draggedRow && draggedRow !== row) row.classList.add('drag-over');
            });
            row.addEventListener('dragleave', function () { row.classList.remove('drag-over'); });
            row.addEventListener('drop', function (e) {
                e.preventDefault();
                row.classList.remove('drag-over');
                if (!draggedRow || draggedRow === row) return;
                const rect = row.getBoundingClientRect();
                const before = (e.clientY - rect.top) < rect.height / 2;
                tbody.insertBefore(draggedRow, before ? row : row.nextSibling);
                saveOrder();
            });
        });

        function saveOrder() {
            const orderedIds = Array.from(tbody.querySelectorAll('tr[data-intro-id]'))
                .map(r => r.dataset.introId);
            apiCall(buildUrl('/reorder'), { orderedIds: orderedIds }, {
                onSuccess: () => {
                    window.TobyToast.show('Order saved.', { type: 'success', duration: 2000 });
                    // Update slot numbers in first column
                    tbody.querySelectorAll('tr[data-intro-id]').forEach((r, i) => {
                        const idx = r.querySelector('.slot-index');
                        if (idx) idx.textContent = String(i + 1);
                    });
                },
                onError: (r) => {
                    const msg = r ? (r.error || 'Reorder failed. Reloading.') : 'Network error. Reloading.';
                    window.TobyToast.show(msg, { type: 'error' });
                    setTimeout(() => location.reload(), 800);
                }
            });
        }
    }

    // --- Super-user member picker ---
    const memberSelect = document.getElementById('memberSelect');
    if (memberSelect) {
        memberSelect.addEventListener('change', function () {
            const q = memberSelect.value;
            const target = q ? '?targetDiscordId=' + encodeURIComponent(q) : '';
            location.href = '/intro/' + cfg.guildId + target;
        });
    }

    // --- Helpers ---
    function apiPostJson(url, body) {
        const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
        if (cfg.csrfToken) headers[cfg.csrfHeader] = cfg.csrfToken;
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers,
            body: JSON.stringify(body)
        }).then(r => r.json().catch(() => ({ ok: r.ok, error: r.ok ? null : 'Request failed.' })));
    }

    // Wraps apiPostJson so callers only supply success/error branches. Network
    // failures go through onError with r = null so the same handler can cover both.
    function apiCall(url, body, { onSuccess, onError }) {
        return apiPostJson(url, body)
            .then(r => { if (r.ok) onSuccess(r); else onError(r); })
            .catch(() => onError(null));
    }

    function buildUrl(suffix) {
        const q = cfg.targetDiscordId ? '?targetDiscordId=' + encodeURIComponent(cfg.targetDiscordId) : '';
        return '/intro/' + cfg.guildId + suffix + q;
    }

    function formatBytes(n) {
        if (n < 1024) return n + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
        return (n / 1024 / 1024).toFixed(1) + ' MB';
    }

    function formatDuration(s) {
        const m = Math.floor(s / 60);
        const sec = s % 60;
        return m + ':' + String(sec).padStart(2, '0');
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        })[c]);
    }
    function escapeAttr(s) { return escapeHtml(s); }
}

if (typeof document !== 'undefined' && typeof window !== 'undefined') {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initIntroPage);
    } else {
        initIntroPage();
    }
    // Expose ▶ handler to inline onclick bindings that still exist in the template
    window.togglePlay = togglePlay;
    window.toggleInput = toggleInput;
}

if (typeof module !== 'undefined') {
    module.exports = { toggleInput, togglePlay };
}
