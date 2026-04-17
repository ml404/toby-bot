(function () {
    let activeBackdrop = null;
    let lastFocused = null;

    /**
     * Show a confirmation modal.
     * @param {Object} opts
     * @param {string} opts.title
     * @param {string} [opts.body]
     * @param {string} [opts.confirmLabel]
     * @param {string} [opts.cancelLabel]
     * @param {'danger'|'primary'} [opts.confirmStyle]
     * @returns {Promise<boolean>} resolves true on confirm, false on cancel
     */
    function confirmDialog(opts) {
        return new Promise(function (resolve) {
            lastFocused = document.activeElement;

            const backdrop = document.createElement('div');
            backdrop.className = 'modal-backdrop';
            backdrop.setAttribute('role', 'dialog');
            backdrop.setAttribute('aria-modal', 'true');

            const modal = document.createElement('div');
            modal.className = 'modal';
            modal.setAttribute('tabindex', '-1');

            const titleEl = document.createElement('h2');
            titleEl.textContent = opts.title || 'Are you sure?';
            modal.appendChild(titleEl);
            backdrop.setAttribute('aria-labelledby', '');

            if (opts.body) {
                const bodyEl = document.createElement('p');
                bodyEl.textContent = opts.body;
                modal.appendChild(bodyEl);
            }

            const actions = document.createElement('div');
            actions.className = 'modal-actions';

            const cancelBtn = document.createElement('button');
            cancelBtn.type = 'button';
            cancelBtn.className = 'btn btn-secondary';
            cancelBtn.textContent = opts.cancelLabel || 'Cancel';

            const confirmBtn = document.createElement('button');
            confirmBtn.type = 'button';
            confirmBtn.className = opts.confirmStyle === 'danger' ? 'btn btn-danger' : 'btn';
            confirmBtn.textContent = opts.confirmLabel || 'Confirm';

            actions.appendChild(cancelBtn);
            actions.appendChild(confirmBtn);
            modal.appendChild(actions);
            backdrop.appendChild(modal);
            document.body.appendChild(backdrop);
            activeBackdrop = backdrop;

            // Focus trap setup
            const focusables = [cancelBtn, confirmBtn];
            confirmBtn.focus();

            function close(result) {
                document.removeEventListener('keydown', onKey);
                if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
                activeBackdrop = null;
                if (lastFocused && typeof lastFocused.focus === 'function') lastFocused.focus();
                resolve(result);
            }

            function onKey(e) {
                if (e.key === 'Escape') { e.preventDefault(); close(false); return; }
                if (e.key === 'Tab') {
                    const idx = focusables.indexOf(document.activeElement);
                    if (e.shiftKey) {
                        if (idx <= 0) { e.preventDefault(); focusables[focusables.length - 1].focus(); }
                    } else {
                        if (idx === focusables.length - 1) { e.preventDefault(); focusables[0].focus(); }
                    }
                }
            }

            document.addEventListener('keydown', onKey);
            cancelBtn.addEventListener('click', function () { close(false); });
            confirmBtn.addEventListener('click', function () { close(true); });
            backdrop.addEventListener('click', function (e) { if (e.target === backdrop) close(false); });
        });
    }

    window.TobyModal = { confirm: confirmDialog };

    if (typeof module !== 'undefined') {
        module.exports = { confirmDialog: confirmDialog };
    }
})();
