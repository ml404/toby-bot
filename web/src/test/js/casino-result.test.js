// Pure-DOM unit test for the shared casino-result helper. Loaded via the
// browser global the production JS uses (window.TobyCasinoResult).
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-topup');
require('../../main/resources/static/js/casino-result');

describe('TobyCasinoResult.render', () => {
    let el;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        el = document.getElementById('r');
    });

    test('no-op when resultEl is null', () => {
        expect(() =>
            window.TobyCasinoResult.render({
                resultEl: null,
                body: { win: true },
                classPrefix: 'slots',
                winLineHtml: 'WIN',
                loseLineHtml: 'LOSE',
            })
        ).not.toThrow();
    });

    test('win path adds the -result-win class and renders the win line', () => {
        window.TobyCasinoResult.render({
            resultEl: el,
            body: { win: true, net: 100 },
            classPrefix: 'slots',
            winLineHtml: '<strong>+100 credits</strong>',
            loseLineHtml: 'LOSE',
        });
        expect(el.classList.contains('slots-result-win')).toBe(true);
        expect(el.classList.contains('slots-result-lose')).toBe(false);
        expect(el.innerHTML).toContain('+100 credits');
        expect(el.innerHTML).not.toContain('LOSE');
    });

    test('lose path adds the -result-lose class and renders the lose line', () => {
        window.TobyCasinoResult.render({
            resultEl: el,
            body: { win: false, net: -50 },
            classPrefix: 'dice',
            winLineHtml: 'WIN',
            loseLineHtml: 'lost <strong>50 credits</strong>',
        });
        expect(el.classList.contains('dice-result-lose')).toBe(true);
        expect(el.classList.contains('dice-result-win')).toBe(false);
        expect(el.innerHTML).toContain('50 credits');
        expect(el.innerHTML).not.toContain('WIN');
    });

    test('clears all three game-prefixed classes before re-rendering', () => {
        // Manually pre-stick all three classes on the element to simulate
        // a previous render — the helper must drop them all so a fresh
        // result doesn't carry over old win/lose/jackpot styling.
        el.classList.add('coinflip-result-win', 'coinflip-result-lose', 'coinflip-result-jackpot');

        window.TobyCasinoResult.render({
            resultEl: el,
            body: { win: false, net: -10 },
            classPrefix: 'coinflip',
            winLineHtml: 'WIN',
            loseLineHtml: 'lose body',
        });

        expect(el.classList.contains('coinflip-result-win')).toBe(false);
        expect(el.classList.contains('coinflip-result-jackpot')).toBe(false);
        expect(el.classList.contains('coinflip-result-lose')).toBe(true);
    });

    test('jackpot win adds the -result-jackpot class and prepends the banner', () => {
        window.TobyCasinoResult.render({
            resultEl: el,
            body: { win: true, net: 100, jackpotPayout: 999 },
            classPrefix: 'slots',
            winLineHtml: '<strong>+100 credits</strong>',
            loseLineHtml: 'LOSE',
        });
        expect(el.classList.contains('slots-result-jackpot')).toBe(true);
        expect(el.innerHTML).toContain('JACKPOT');
        expect(el.innerHTML).toContain('+999 credits');
    });

    test('lose with lossTribute appends the +N to jackpot suffix', () => {
        window.TobyCasinoResult.render({
            resultEl: el,
            body: { win: false, net: -50, lossTribute: 5 },
            classPrefix: 'dice',
            winLineHtml: 'WIN',
            loseLineHtml: 'lost 50 credits',
        });
        expect(el.innerHTML).toContain('+5 to jackpot');
    });

    test('top-up prefix prepends to win and lose alike', () => {
        window.TobyCasinoResult.render({
            resultEl: el,
            body: { win: true, net: 100, soldTobyCoins: 7, newPrice: 12.5 },
            classPrefix: 'slots',
            winLineHtml: 'WIN_LINE',
            loseLineHtml: 'LOSE_LINE',
        });
        // TobyTopUp.soldPrefixHtml mentions the sold count somewhere.
        expect(el.innerHTML).toContain('7');
        expect(el.innerHTML).toContain('WIN_LINE');
    });

    test('removes hidden attribute on render', () => {
        el.hidden = true;
        window.TobyCasinoResult.render({
            resultEl: el,
            body: { win: true, net: 1 },
            classPrefix: 'slots',
            winLineHtml: 'x',
            loseLineHtml: 'y',
        });
        expect(el.hidden).toBe(false);
    });
});
