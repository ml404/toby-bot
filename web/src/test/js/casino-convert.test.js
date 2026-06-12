// Unit tests for the coin-convert panel helpers exposed on
// window.TobyCoinConvert. The DOM-wiring IIFE only runs on a real game
// page (guarded by `main[data-guild-id]`), so the meaningful regression
// surface is the pure parse/estimate/render helpers.

const Convert = require('../../main/resources/static/js/casino-convert');

describe('TobyCoinConvert.parseHoldings', () => {
    test('keeps valid rows and drops non-positive amounts', () => {
        const out = Convert.parseHoldings(
            '[{"symbol":"MOON","name":"Moonpup","amount":12,"price":50},' +
            '{"symbol":"RUFF","name":"Rufftoken","amount":0,"price":10}]'
        );
        expect(out.length).toBe(1);
        expect(out[0].symbol).toBe('MOON');
    });

    test('returns [] for empty, null, non-array or garbled input', () => {
        expect(Convert.parseHoldings('')).toEqual([]);
        expect(Convert.parseHoldings(null)).toEqual([]);
        expect(Convert.parseHoldings('not json')).toEqual([]);
        expect(Convert.parseHoldings('{"symbol":"MOON"}')).toEqual([]);
    });
});

describe('TobyCoinConvert.estimateValue', () => {
    test('floors price times amount', () => {
        expect(Convert.estimateValue(50.4, 12)).toBe(604);
        expect(Convert.estimateValue(100, 5)).toBe(500);
    });

    test('is zero when price or amount is missing', () => {
        expect(Convert.estimateValue(0, 12)).toBe(0);
        expect(Convert.estimateValue(50, 0)).toBe(0);
    });
});

describe('TobyCoinConvert.renderPanel', () => {
    test('returns null when the player holds nothing', () => {
        expect(Convert.renderPanel(document, [])).toBeNull();
        expect(Convert.renderPanel(document, null)).toBeNull();
    });

    test('builds one row per coin with a sell button and amount input', () => {
        const panel = Convert.renderPanel(document, [
            { symbol: 'MOON', name: 'Moonpup', amount: 12, price: 50 },
            { symbol: 'TOBY', name: 'Toby Coin', amount: 5, price: 100 },
        ]);
        expect(panel).not.toBeNull();

        const rows = panel.querySelectorAll('.casino-convert-row');
        expect(rows.length).toBe(2);
        expect(rows[0].dataset.coin).toBe('MOON');

        const sellButtons = panel.querySelectorAll('.casino-convert-sell');
        expect(sellButtons.length).toBe(2);
        expect(sellButtons[0].dataset.coin).toBe('MOON');

        const amount = rows[0].querySelector('.casino-convert-amount');
        expect(amount.value).toBe('12');
    });
});
