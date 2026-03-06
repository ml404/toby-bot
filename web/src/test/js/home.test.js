const { toggleNav } = require('../../main/resources/static/js/home');

// ---------------------------------------------------------------------------
// toggleNav
// ---------------------------------------------------------------------------

describe('toggleNav', () => {
    beforeEach(() => {
        document.body.innerHTML = `<div id="nav-menu"></div>`;
    });

    test('adds "open" class and returns true when menu is closed', () => {
        const result = toggleNav();

        expect(result).toBe(true);
        expect(document.getElementById('nav-menu').classList.contains('open')).toBe(true);
    });

    test('removes "open" class and returns false when menu is already open', () => {
        const menu = document.getElementById('nav-menu');
        menu.classList.add('open');

        const result = toggleNav();

        expect(result).toBe(false);
        expect(menu.classList.contains('open')).toBe(false);
    });

    test('toggles open twice to return to closed state', () => {
        toggleNav(); // open
        const result = toggleNav(); // close

        expect(result).toBe(false);
        expect(document.getElementById('nav-menu').classList.contains('open')).toBe(false);
    });

    test('returns false when nav-menu element does not exist', () => {
        document.body.innerHTML = '';

        const result = toggleNav();

        expect(result).toBe(false);
    });
});
