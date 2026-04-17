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
        close.addEventListener('click', remove);
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

    window.TobyToast = { show: showToast };

    if (typeof module !== 'undefined') {
        module.exports = { showToast: showToast };
    }
})();
