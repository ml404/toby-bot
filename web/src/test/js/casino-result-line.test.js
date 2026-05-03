const { factory } = require('../../main/resources/static/js/casino-result-line');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('TobyCasinoResultLine.factory', () => {
    let resultEl;
    let flashEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div><section id="t"></section>';
        resultEl = document.getElementById('r');
        flashEl = document.getElementById('t');
    });

    test('builds a renderer that emits the win line and toggles the win class', () => {
        const render = factory({
            classPrefix: 'foo',
            winLine: (b) => 'won ' + b.net,
            loseLine: () => 'lost',
        });
        render(resultEl, { win: true, net: 200 }, flashEl);
        expect(resultEl.classList.contains('foo-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('won 200');
    });

    test('lose path emits the lose line and toggles the lose class', () => {
        const render = factory({
            classPrefix: 'foo',
            winLine: () => 'won',
            loseLine: (b) => 'lost ' + Math.abs(b.net),
        });
        render(resultEl, { win: false, net: -75 }, flashEl);
        expect(resultEl.classList.contains('foo-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('lost 75');
    });

    test('flashes a chip stack on the flash target on a win', () => {
        const render = factory({
            classPrefix: 'foo',
            winLine: () => 'won',
            loseLine: () => 'lost',
        });
        render(resultEl, { win: true, net: 500 }, flashEl);
        expect(flashEl.querySelector('.casino-chip-stack')).not.toBeNull();
    });
});
