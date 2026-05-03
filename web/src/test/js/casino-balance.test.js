// Unit tests for the shared `TobyBalance.update` helper. The function
// has to be defensive across every caller (one-shot games, multi-step
// blackjack/poker, tip) so its no-op cases need to be explicit:
//   - missing element  → no-op (page without a wallet readout)
//   - non-number value → no-op (server omitted `newBalance`, or sent a
//                        bad type — don't clobber the existing display)
//   - 0                → must write '0' (regression guard against a
//                        truthy-check that drops a legitimate zero balance)
require('../../main/resources/static/js/casino-balance');

describe('TobyBalance.update', () => {
    let el;

    beforeEach(() => {
        document.body.innerHTML = '<strong id="bal">starting</strong>';
        el = document.getElementById('bal');
    });

    test('writes the new balance as text when it is a number', () => {
        window.TobyBalance.update(el, 1234);
        expect(el.textContent).toBe('1234');
    });

    test('writes 0 — never let a truthy-check drop a real zero balance', () => {
        window.TobyBalance.update(el, 0);
        expect(el.textContent).toBe('0');
    });

    test('no-ops on a null element (page without a wallet readout)', () => {
        expect(() => window.TobyBalance.update(null, 100)).not.toThrow();
    });

    test('no-ops on undefined newBalance — preserves existing display', () => {
        window.TobyBalance.update(el, undefined);
        expect(el.textContent).toBe('starting');
    });

    test('no-ops on null newBalance — preserves existing display', () => {
        window.TobyBalance.update(el, null);
        expect(el.textContent).toBe('starting');
    });

    test('no-ops on a non-number (stringly-typed bad backend) — preserves display', () => {
        window.TobyBalance.update(el, '999');
        expect(el.textContent).toBe('starting');
    });
});
