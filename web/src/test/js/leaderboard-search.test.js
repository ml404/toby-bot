const { filterStandingsRows } = require('../../main/resources/static/js/leaderboard');

// Builds standings rows with a data-name attribute (as the template emits,
// lower-cased) and returns the row elements.
function rows(...names) {
    document.body.innerHTML =
        '<table><tbody id="lb-standings-body">' +
        names.map((n) => `<tr data-name="${n.toLowerCase()}"><td>${n}</td></tr>`).join('') +
        '</tbody></table>';
    return Array.from(document.querySelectorAll('#lb-standings-body tr'));
}

describe('filterStandingsRows', () => {
    test('shows only rows whose name contains the query (case-insensitive)', () => {
        const r = rows('Alice', 'Bob', 'Albert');
        const visible = filterStandingsRows(r, 'AL');
        expect(visible).toBe(2);
        expect(r[0].hidden).toBe(false); // Alice
        expect(r[1].hidden).toBe(true); // Bob
        expect(r[2].hidden).toBe(false); // Albert
    });

    test('empty and whitespace-only queries show every row', () => {
        const r = rows('Alice', 'Bob');
        expect(filterStandingsRows(r, '')).toBe(2);
        expect(r.every((tr) => tr.hidden === false)).toBe(true);

        expect(filterStandingsRows(r, '   ')).toBe(2);
        expect(r.every((tr) => tr.hidden === false)).toBe(true);
    });

    test('no matches hides everything and returns 0', () => {
        const r = rows('Alice', 'Bob');
        expect(filterStandingsRows(r, 'zzz')).toBe(0);
        expect(r.every((tr) => tr.hidden === true)).toBe(true);
    });

    test('matches substrings anywhere in the name', () => {
        const r = rows('xXEpicGamerXx', 'plainname');
        expect(filterStandingsRows(r, 'epicgamer')).toBe(1);
        expect(r[0].hidden).toBe(false);
        expect(r[1].hidden).toBe(true);
    });
});
