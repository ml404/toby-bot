// Searchable channel picker. Finds every <select data-channel-picker>
// on the page and replaces it with a typeahead combobox, keeping the
// native <select> hidden in the DOM so the form posts the same field
// and the page still works with JavaScript disabled.
//
// Mirrors static/js/userPicker.js and reuses the same .user-picker__*
// CSS classes. Differences from userPicker:
//   - single-select only (no chips, no multi branch)
//   - no avatar / social credit rendering
//   - honours `disabled` on the native <select> by locking the wrapper
//   - keeps the empty-value option visible as a selectable first row,
//     so users can return to fallback semantics like "— system channel —"
//     after picking a channel without reloading the page.

'use strict';

function initChannelPicker(select) {
    if (select.dataset.channelPickerReady === '1') return;
    select.dataset.channelPickerReady = '1';

    const wrapper = select.closest('.user-picker');
    if (!wrapper) return;
    const placeholder = wrapper.dataset.placeholder || 'Search…';

    select.classList.add('user-picker__native');
    select.setAttribute('aria-hidden', 'true');
    select.setAttribute('tabindex', '-1');

    const isDisabled = select.disabled;
    if (isDisabled) wrapper.classList.add('user-picker--disabled');

    const fieldId = select.id || ('channelPicker-' + Math.random().toString(36).slice(2));
    const listId = fieldId + '-list';

    const field = document.createElement('div');
    field.className = 'user-picker__field';
    field.setAttribute('role', 'combobox');
    field.setAttribute('aria-haspopup', 'listbox');
    field.setAttribute('aria-expanded', 'false');
    field.setAttribute('aria-controls', listId);
    field.tabIndex = isDisabled ? -1 : 0;
    if (isDisabled) field.setAttribute('aria-disabled', 'true');

    const valueLabel = document.createElement('span');
    valueLabel.className = 'user-picker__value';

    const caret = document.createElement('span');
    caret.className = 'user-picker__caret';
    caret.setAttribute('aria-hidden', 'true');
    caret.textContent = '▾';

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

    panel.appendChild(input);
    panel.appendChild(list);
    wrapper.appendChild(field);
    wrapper.appendChild(panel);

    // Cache the picker options. Unlike userPicker we keep the empty-
    // value option in the list (rendered first, never filtered out) so
    // users can pick it to return to fallback semantics like
    // "— system channel —".
    const options = Array.from(select.options).map((opt, i) => ({
        el: opt,
        value: opt.value,
        label: (opt.textContent || '').trim(),
        labelLc: (opt.textContent || '').trim().toLowerCase(),
        isEmpty: opt.value === '',
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
            if (typeof active.scrollIntoView === 'function') {
                active.scrollIntoView({ block: 'nearest' });
            }
        } else {
            field.removeAttribute('aria-activedescendant');
        }
    }

    function renderList(query) {
        const q = (query || '').trim().toLowerCase();
        list.innerHTML = '';
        let shown = 0;
        options.forEach(o => {
            // Empty-value option is always visible — it represents the
            // fallback / "none" choice and users should be able to pick
            // it back without clearing the query first.
            if (!o.isEmpty && q && !o.labelLc.includes(q)) return;
            const li = document.createElement('li');
            li.className = 'user-picker__option';
            li.setAttribute('role', 'option');
            li.id = o.domId;
            li.dataset.value = o.value;
            const isSelected = select.value === o.value;
            if (isSelected) {
                li.classList.add('is-selected');
                li.setAttribute('aria-selected', 'true');
            }
            const text = document.createElement('span');
            text.className = 'user-picker__option-label';
            text.textContent = o.label;
            li.appendChild(text);
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

    function renderValue() {
        const sel = select.selectedOptions[0];
        if (sel && sel.value) {
            valueLabel.textContent = sel.textContent.trim();
            valueLabel.classList.remove('user-picker__value--placeholder');
        } else if (sel && sel.value === '') {
            // Empty-value option is selected — show its label, not the
            // placeholder, so users see the active fallback choice.
            valueLabel.textContent = sel.textContent.trim();
            valueLabel.classList.add('user-picker__value--placeholder');
        } else {
            valueLabel.textContent = placeholder;
            valueLabel.classList.add('user-picker__value--placeholder');
        }
    }

    function sync() {
        renderValue();
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function pick(value) {
        const opt = Array.from(select.options).find(o => o.value === value);
        if (!opt) return;
        select.value = value;
        sync();
        close();
    }

    function open() {
        if (isDisabled) return;
        if (!panel.hidden) return;
        panel.hidden = false;
        field.setAttribute('aria-expanded', 'true');
        input.value = '';
        renderList('');
        const items = visibleItems();
        highlightedIndex = items.findIndex(li => li.classList.contains('is-selected'));
        updateHighlight();
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

    // Redirect clicks on the associated <label for="…"> to the visible
    // field. Without this, label clicks focus the hidden native <select>
    // and nothing on screen reacts.
    if (select.id) {
        const escaped = (typeof CSS !== 'undefined' && typeof CSS.escape === 'function')
            ? CSS.escape(select.id)
            // JSDOM and some older browsers don't expose CSS.escape.
            // Escape anything that isn't a CSS identifier char.
            : select.id.replace(/([^\w-])/g, '\\$1');
        const ownLabel = document.querySelector('label[for="' + escaped + '"]');
        if (ownLabel) {
            ownLabel.addEventListener('click', e => {
                e.preventDefault();
                // Without stopPropagation, the document-level
                // click-outside listener below would fire next and
                // close the panel we just opened (the label sits
                // outside the wrapper).
                e.stopPropagation();
                open();
            });
        }
    }

    renderValue();
}

function initAllChannelPickers(root) {
    (root || document).querySelectorAll('select[data-channel-picker]').forEach(initChannelPicker);
}

if (typeof document !== 'undefined' && typeof window !== 'undefined') {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => initAllChannelPickers());
    } else {
        initAllChannelPickers();
    }
    window.ChannelPicker = { init: initAllChannelPickers };
}

if (typeof module !== 'undefined') {
    module.exports = {
        init: initChannelPicker,
        initAll: initAllChannelPickers
    };
}
