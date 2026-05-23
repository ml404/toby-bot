const channelPicker = require('../../main/resources/static/js/channelPicker');

function mount(selectHtml) {
    document.body.innerHTML = `<div class="user-picker" data-placeholder="Type to filter…">${selectHtml}</div>`;
    channelPicker.initAll();
    return document.body.querySelector('.user-picker');
}

function textOptionsHtml(extraEmpty = false, valuePrefix = '#') {
    const empty = extraEmpty
        ? `<option value="">— system channel —</option>`
        : '';
    return `
        ${empty}
        <option value="1" data-prefix="${valuePrefix}">${valuePrefix}general</option>
        <option value="2" data-prefix="${valuePrefix}">${valuePrefix}announcements</option>
        <option value="3" data-prefix="${valuePrefix}">${valuePrefix}gaming</option>
    `;
}

function selectHtml({ empty = false, disabled = false, id = '' } = {}) {
    const dis = disabled ? 'disabled' : '';
    const idAttr = id ? `id="${id}"` : '';
    return `<select ${idAttr} data-channel-picker ${dis}>${textOptionsHtml(empty)}</select>`;
}

function visibleOptionLabels(wrapper) {
    return Array.from(wrapper.querySelectorAll('.user-picker__option .user-picker__option-label'))
        .map(el => el.textContent);
}

function openPanel(wrapper) {
    wrapper.querySelector('.user-picker__field').click();
}

beforeEach(() => {
    document.body.innerHTML = '';
});

describe('initAll enhancement', () => {
    test('builds combobox shell and hides native select', () => {
        const wrapper = mount(selectHtml());
        const native = wrapper.querySelector('select[data-channel-picker]');
        expect(wrapper.querySelector('.user-picker__field')).not.toBeNull();
        expect(wrapper.querySelector('.user-picker__panel')).not.toBeNull();
        expect(wrapper.querySelector('.user-picker__input')).not.toBeNull();
        expect(native.classList.contains('user-picker__native')).toBe(true);
        expect(native.getAttribute('aria-hidden')).toBe('true');
    });

    test('is idempotent — running initAll twice does not double-build', () => {
        const wrapper = mount(selectHtml());
        channelPicker.initAll();
        expect(wrapper.querySelectorAll('.user-picker__field').length).toBe(1);
        expect(wrapper.querySelectorAll('.user-picker__panel').length).toBe(1);
    });
});

describe('typeahead filtering', () => {
    test('filters options by case-insensitive substring on the rendered label', () => {
        const wrapper = mount(selectHtml());
        openPanel(wrapper);
        const input = wrapper.querySelector('.user-picker__input');
        input.value = 'GEN';
        input.dispatchEvent(new Event('input'));
        const labels = visibleOptionLabels(wrapper);
        // "general" matches case-insensitively; "announcements" and
        // "gaming" do not contain "gen".
        expect(labels).toContain('#general');
        expect(labels).not.toContain('#announcements');
        expect(labels).not.toContain('#gaming');
    });

    test('the value-prefix is part of the searchable label', () => {
        const wrapper = mount(selectHtml());
        openPanel(wrapper);
        const input = wrapper.querySelector('.user-picker__input');
        input.value = '#general';
        input.dispatchEvent(new Event('input'));
        expect(visibleOptionLabels(wrapper)).toContain('#general');
    });

    test('shows the "No matches" empty state when query matches nothing', () => {
        const wrapper = mount(selectHtml());
        openPanel(wrapper);
        const input = wrapper.querySelector('.user-picker__input');
        input.value = 'nope-zzz';
        input.dispatchEvent(new Event('input'));
        expect(wrapper.querySelector('.user-picker__empty')).not.toBeNull();
    });
});

describe('picking a channel', () => {
    test('clicking an option sets the native select value and fires a bubbling change event', () => {
        const wrapper = mount(selectHtml());
        const native = wrapper.querySelector('select[data-channel-picker]');
        const onChange = jest.fn();
        native.addEventListener('change', onChange);

        openPanel(wrapper);
        const second = wrapper.querySelectorAll('.user-picker__option')[1];
        second.click();

        expect(native.value).toBe(second.dataset.value);
        expect(onChange).toHaveBeenCalledTimes(1);
        expect(onChange.mock.calls[0][0].bubbles).toBe(true);
    });

    test('after picking, the field shows the chosen label and the panel closes', () => {
        const wrapper = mount(selectHtml());
        openPanel(wrapper);
        const first = wrapper.querySelector('.user-picker__option');
        const label = first.textContent.trim();
        first.click();

        expect(wrapper.querySelector('.user-picker__value').textContent).toBe(label);
        expect(wrapper.querySelector('.user-picker__panel').hidden).toBe(true);
    });
});

describe('empty-value option', () => {
    test('is rendered as a selectable first row in the list', () => {
        const wrapper = mount(selectHtml({ empty: true }));
        openPanel(wrapper);
        const labels = visibleOptionLabels(wrapper);
        expect(labels[0]).toBe('— system channel —');
    });

    test('stays visible even when the typed query does not match its label', () => {
        const wrapper = mount(selectHtml({ empty: true }));
        openPanel(wrapper);
        const input = wrapper.querySelector('.user-picker__input');
        input.value = 'general';
        input.dispatchEvent(new Event('input'));
        // Fallback row is the always-on first row; query of "general" should
        // not hide it even though its label doesn't include "general".
        expect(visibleOptionLabels(wrapper)).toContain('— system channel —');
    });

    test('picking the empty row clears the native value and fires change', () => {
        const wrapper = mount(selectHtml({ empty: true }));
        const native = wrapper.querySelector('select[data-channel-picker]');
        // Start from a non-empty selection.
        native.value = '2';

        const onChange = jest.fn();
        native.addEventListener('change', onChange);

        openPanel(wrapper);
        const emptyRow = wrapper.querySelector('.user-picker__option');
        emptyRow.click();

        expect(native.value).toBe('');
        expect(onChange).toHaveBeenCalledTimes(1);
    });
});

describe('disabled state', () => {
    test('locks the wrapper with --disabled class', () => {
        const wrapper = mount(selectHtml({ disabled: true }));
        expect(wrapper.classList.contains('user-picker--disabled')).toBe(true);
    });

    test('clicking the field does not open the panel', () => {
        const wrapper = mount(selectHtml({ disabled: true }));
        openPanel(wrapper);
        expect(wrapper.querySelector('.user-picker__panel').hidden).toBe(true);
    });

    test('native select stays disabled', () => {
        const wrapper = mount(selectHtml({ disabled: true }));
        expect(wrapper.querySelector('select[data-channel-picker]').disabled).toBe(true);
    });
});

describe('keyboard navigation', () => {
    test('Escape on the search input closes the panel', () => {
        const wrapper = mount(selectHtml());
        openPanel(wrapper);
        const input = wrapper.querySelector('.user-picker__input');
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        expect(wrapper.querySelector('.user-picker__panel').hidden).toBe(true);
    });

    test('ArrowDown highlights the next item; Enter picks it', () => {
        const wrapper = mount(selectHtml());
        const native = wrapper.querySelector('select[data-channel-picker]');
        openPanel(wrapper);
        const input = wrapper.querySelector('.user-picker__input');
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        const highlighted = wrapper.querySelector('.user-picker__option.is-highlighted');
        expect(highlighted).not.toBeNull();
        const targetValue = highlighted.dataset.value;
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
        expect(native.value).toBe(targetValue);
    });
});

describe('label-for redirect', () => {
    test('clicking the associated label opens the picker panel', () => {
        document.body.innerHTML = `
            <label for="ch-test">Channel</label>
            <div class="user-picker" data-placeholder="Type to filter…">
                ${selectHtml({ id: 'ch-test' })}
            </div>
        `;
        channelPicker.initAll();
        // Dispatch a plain click event rather than calling label.click() —
        // JSDOM's label.click() synthesizes a secondary click on the
        // associated form control, which fires the document-level
        // click-outside listener and immediately re-closes the panel.
        // Real browsers don't show this quirk; dispatching the event
        // directly tests the listener we care about in isolation.
        const label = document.querySelector('label[for="ch-test"]');
        label.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        const panel = document.body.querySelector('.user-picker__panel');
        expect(panel.hidden).toBe(false);
    });
});
