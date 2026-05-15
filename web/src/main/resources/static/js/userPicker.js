// Searchable user picker. Finds every <select data-user-picker> on the
// page and replaces it with a typeahead combobox (chips when multiple),
// keeping the native <select> hidden in the DOM so the form posts the
// same fields and the page still works with JavaScript disabled.
(function () {
    'use strict';

    function init(select) {
        if (select.dataset.userPickerReady === '1') return;
        select.dataset.userPickerReady = '1';

        const multiple = select.multiple;
        const wrapper = select.closest('.user-picker');
        if (!wrapper) return;
        const placeholder = wrapper.dataset.placeholder || 'Search…';

        select.classList.add('user-picker__native');
        select.setAttribute('aria-hidden', 'true');
        select.setAttribute('tabindex', '-1');

        const fieldId = select.id || ('userPicker-' + Math.random().toString(36).slice(2));
        const listId = fieldId + '-list';

        const field = document.createElement('div');
        field.className = 'user-picker__field';
        field.setAttribute('role', 'combobox');
        field.setAttribute('aria-haspopup', 'listbox');
        field.setAttribute('aria-expanded', 'false');
        field.setAttribute('aria-controls', listId);
        field.tabIndex = 0;

        const chipBar = document.createElement('span');
        chipBar.className = 'user-picker__chips';

        const valueLabel = document.createElement('span');
        valueLabel.className = 'user-picker__value';

        const caret = document.createElement('span');
        caret.className = 'user-picker__caret';
        caret.setAttribute('aria-hidden', 'true');
        caret.textContent = '▾';

        field.appendChild(chipBar);
        field.appendChild(valueLabel);
        field.appendChild(caret);

        const panel = document.createElement('div');
        panel.className = 'user-picker__panel';
        panel.hidden = true;

        const input = document.createElement('input');
        input.type = 'search';
        input.className = 'user-picker__input';
        input.placeholder = 'Type to filter…';
        input.autocomplete = 'off';
        input.setAttribute('aria-controls', listId);

        const list = document.createElement('ul');
        list.className = 'user-picker__list';
        list.id = listId;
        list.setAttribute('role', 'listbox');
        if (multiple) list.setAttribute('aria-multiselectable', 'true');

        panel.appendChild(input);
        panel.appendChild(list);
        wrapper.appendChild(field);
        wrapper.appendChild(panel);

        // Cache the picker-eligible options. The empty-value option (if
        // any) stays in the <select> for form fallback but is never
        // shown in the combobox list — picking nothing from the list
        // means leaving the select on its empty value.
        const options = Array.from(select.options)
            .filter(opt => opt.value !== '')
            .map((opt, i) => ({
                el: opt,
                value: opt.value,
                label: (opt.textContent || '').trim(),
                labelLc: (opt.textContent || '').trim().toLowerCase(),
                avatar: opt.dataset.avatar || '',
                domId: listId + '-opt-' + i
            }));

        let highlightedIndex = -1;

        function visibleItems() {
            return Array.from(list.querySelectorAll('.user-picker__option'));
        }

        function updateHighlight() {
            const items = visibleItems();
            items.forEach((li, i) => li.classList.toggle('is-highlighted', i === highlightedIndex));
            const active = items[highlightedIndex];
            if (active) {
                field.setAttribute('aria-activedescendant', active.id);
                active.scrollIntoView({ block: 'nearest' });
            } else {
                field.removeAttribute('aria-activedescendant');
            }
        }

        function renderList(query) {
            const q = (query || '').trim().toLowerCase();
            list.innerHTML = '';
            let shown = 0;
            options.forEach(o => {
                if (q && !o.labelLc.includes(q)) return;
                const li = document.createElement('li');
                li.className = 'user-picker__option';
                li.setAttribute('role', 'option');
                li.id = o.domId;
                li.dataset.value = o.value;
                const isSelected = multiple ? o.el.selected : (select.value === o.value);
                if (isSelected) {
                    li.classList.add('is-selected');
                    li.setAttribute('aria-selected', 'true');
                }
                if (o.avatar) {
                    const img = document.createElement('img');
                    img.className = 'user-picker__avatar';
                    img.src = o.avatar;
                    img.alt = '';
                    li.appendChild(img);
                }
                const text = document.createElement('span');
                text.className = 'user-picker__option-label';
                text.textContent = o.label;
                li.appendChild(text);
                if (multiple && isSelected) {
                    const check = document.createElement('span');
                    check.className = 'user-picker__check';
                    check.setAttribute('aria-hidden', 'true');
                    check.textContent = '✓';
                    li.appendChild(check);
                }
                list.appendChild(li);
                shown++;
            });
            if (shown === 0) {
                const empty = document.createElement('li');
                empty.className = 'user-picker__empty';
                empty.textContent = 'No matches';
                list.appendChild(empty);
            }
            highlightedIndex = -1;
            updateHighlight();
        }

        function renderChips() {
            chipBar.innerHTML = '';
            if (!multiple) {
                const sel = select.selectedOptions[0];
                if (sel && sel.value) {
                    valueLabel.textContent = sel.textContent.trim();
                    valueLabel.classList.remove('user-picker__value--placeholder');
                } else {
                    valueLabel.textContent = placeholder;
                    valueLabel.classList.add('user-picker__value--placeholder');
                }
                return;
            }
            const selected = Array.from(select.selectedOptions).filter(o => o.value !== '');
            if (selected.length === 0) {
                valueLabel.textContent = placeholder;
                valueLabel.classList.add('user-picker__value--placeholder');
                return;
            }
            valueLabel.textContent = '';
            valueLabel.classList.remove('user-picker__value--placeholder');
            selected.forEach(opt => {
                const chip = document.createElement('span');
                chip.className = 'user-picker__chip';
                chip.dataset.value = opt.value;
                const lab = document.createElement('span');
                lab.textContent = opt.textContent.trim();
                chip.appendChild(lab);
                const x = document.createElement('button');
                x.type = 'button';
                x.className = 'user-picker__chip-remove';
                x.setAttribute('aria-label', 'Remove ' + opt.textContent.trim());
                x.textContent = '×';
                x.addEventListener('click', e => {
                    e.stopPropagation();
                    opt.selected = false;
                    sync();
                    if (!panel.hidden) renderList(input.value);
                });
                chip.appendChild(x);
                chipBar.appendChild(chip);
            });
        }

        function sync() {
            renderChips();
            select.dispatchEvent(new Event('change', { bubbles: true }));
        }

        function pick(value) {
            const opt = Array.from(select.options).find(o => o.value === value);
            if (!opt) return;
            if (multiple) {
                opt.selected = !opt.selected;
                sync();
                renderList(input.value);
                input.focus();
            } else {
                select.value = value;
                sync();
                close();
            }
        }

        function open() {
            if (!panel.hidden) return;
            panel.hidden = false;
            field.setAttribute('aria-expanded', 'true');
            input.value = '';
            renderList('');
            if (!multiple) {
                const items = visibleItems();
                highlightedIndex = items.findIndex(li => li.classList.contains('is-selected'));
                updateHighlight();
            }
            requestAnimationFrame(() => input.focus());
        }

        function close() {
            if (panel.hidden) return;
            panel.hidden = true;
            field.setAttribute('aria-expanded', 'false');
            input.value = '';
        }

        field.addEventListener('click', open);
        field.addEventListener('keydown', e => {
            if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
                e.preventDefault();
                open();
            }
        });

        input.addEventListener('input', () => renderList(input.value));
        input.addEventListener('keydown', e => {
            const items = visibleItems();
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                if (items.length === 0) return;
                highlightedIndex = (highlightedIndex + 1) % items.length;
                updateHighlight();
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                if (items.length === 0) return;
                highlightedIndex = highlightedIndex <= 0 ? items.length - 1 : highlightedIndex - 1;
                updateHighlight();
            } else if (e.key === 'Enter') {
                e.preventDefault();
                if (highlightedIndex >= 0 && items[highlightedIndex]) {
                    pick(items[highlightedIndex].dataset.value);
                } else if (items.length === 1) {
                    pick(items[0].dataset.value);
                }
            } else if (e.key === 'Escape') {
                e.preventDefault();
                close();
                field.focus();
            } else if (e.key === 'Backspace' && multiple && input.value === '') {
                const sel = Array.from(select.selectedOptions).filter(o => o.value !== '');
                if (sel.length > 0) {
                    sel[sel.length - 1].selected = false;
                    sync();
                    renderList(input.value);
                }
            }
        });

        list.addEventListener('click', e => {
            const li = e.target.closest('.user-picker__option');
            if (li && li.dataset.value != null) pick(li.dataset.value);
        });
        list.addEventListener('mousemove', e => {
            const li = e.target.closest('.user-picker__option');
            if (!li) return;
            const items = visibleItems();
            const i = items.indexOf(li);
            if (i !== highlightedIndex) {
                highlightedIndex = i;
                updateHighlight();
            }
        });

        document.addEventListener('click', e => {
            if (!wrapper.contains(e.target)) close();
        });

        // Redirect clicks on the associated <label for="…"> to the
        // visible field. Without this, label clicks focus the hidden
        // native <select> and nothing on screen reacts.
        if (select.id) {
            try {
                const ownLabel = document.querySelector('label[for="' + CSS.escape(select.id) + '"]');
                if (ownLabel) {
                    ownLabel.addEventListener('click', e => {
                        e.preventDefault();
                        open();
                    });
                }
            } catch (_) { /* CSS.escape unsupported; skip */ }
        }

        renderChips();
    }

    function initAll(root) {
        (root || document).querySelectorAll('select[data-user-picker]').forEach(init);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => initAll());
    } else {
        initAll();
    }

    window.UserPicker = { init: initAll };
})();
