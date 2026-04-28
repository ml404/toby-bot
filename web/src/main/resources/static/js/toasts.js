(function () {
    function ensureStack() {
        let stack = document.querySelector('.toast-stack');
        if (!stack) {
            stack = document.createElement('div');
            stack.className = 'toast-stack';
            stack.setAttribute('role', 'region');
            stack.setAttribute('aria-label', 'Notifications');
            stack.setAttribute('aria-live', 'polite');
            document.body.appendChild(stack);
        }
        return stack;
    }

    /**
     * Show a toast notification.
     * @param {string} message
     * @param {Object} [opts]
     * @param {'success'|'error'|'info'} [opts.type]
     * @param {number} [opts.duration] - milliseconds, 0 = sticky
     * @param {{label: string, onClick: Function}} [opts.action]
     */
    function showToast(message, opts) {
        opts = opts || {};
        const stack = ensureStack();
        const el = document.createElement('div');
        el.className = 'toast toast-' + (opts.type || 'info');
        el.setAttribute('role', opts.type === 'error' ? 'alert' : 'status');

        const text = document.createElement('span');
        text.textContent = message;
        el.appendChild(text);

        if (opts.action) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'toast-action';
            btn.textContent = opts.action.label;
            btn.addEventListener('click', function () {
                try { opts.action.onClick(); } finally { remove(); }
            });
            el.appendChild(btn);
        }

        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'toast-close';
        close.setAttribute('aria-label', 'Dismiss');
        close.innerHTML = '&times;';
        close.addEventListener('click', () => el.remove());
        el.appendChild(close);

        stack.appendChild(el);

        const duration = opts.duration == null ? 4000 : opts.duration;
        let timer = null;
        if (duration > 0) timer = setTimeout(remove, duration);

        function remove() {
            if (timer) clearTimeout(timer);
            if (el.parentNode) el.parentNode.removeChild(el);
        }
        return { dismiss: remove };
    }

    // single unified API
    function toast(msg, type) {
        showToast(msg, { type: type || 'info' });
    }

    window.TobyToasts = {
        show: showToast,
        success: (m) => toast(m, 'success'),
        error: (m) => toast(m, 'error'),
        info: (m) => toast(m, 'info')
    };

    // GLOBAL SHORTCUT (this is what your other files should use)
    window.toast = toast;
})();