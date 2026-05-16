const { renderPlinkoResult } = require('../../main/resources/static/js/plinko');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderPlinkoResult', () => {
    let resultEl;

    beforeEach(() => {
        document.body.innerHTML = '<div id="r"></div>';
        resultEl = document.getElementById('r');
    });

    test('win shows bucket index, multiplier, and net credits', () => {
        renderPlinkoResult(resultEl, {
            win: true, push: false, bucket: 0, multiplier: 12, net: 1100
        });

        expect(resultEl.classList.contains('plinko-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('Bucket 0');
        expect(resultEl.innerHTML).toContain('12×');
        expect(resultEl.innerHTML).toContain('+1100 credits');
    });

    test('lose shows bucket, multiplier, and absolute net loss', () => {
        renderPlinkoResult(resultEl, {
            win: false, push: false, bucket: 4, multiplier: 0, net: -100
        });

        expect(resultEl.classList.contains('plinko-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('Bucket 4');
        expect(resultEl.innerHTML).toContain('0×');
        expect(resultEl.innerHTML).toContain('100 credits');
    });

    test('push (multiplier 1.0) marks as lose-class with refund copy and net 0', () => {
        // Pushes don't tribute and don't roll the jackpot, so the
        // shared result helper treats them like lose for class purposes
        // — but the line copy distinguishes the refund.
        renderPlinkoResult(resultEl, {
            win: false, push: true, bucket: 3, multiplier: 1.0, net: 0
        });

        expect(resultEl.classList.contains('plinko-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('refund');
    });

    test('fractional multiplier renders without trailing zeros', () => {
        renderPlinkoResult(resultEl, {
            win: false, push: false, bucket: 4, multiplier: 0.4, net: -60
        });

        expect(resultEl.innerHTML).toContain('0.4×');
        expect(resultEl.innerHTML).not.toContain('0.40×');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderPlinkoResult(resultEl, {
            win: true, push: false, bucket: 0, multiplier: 40,
            net: 3900, jackpotPayout: 5000
        });

        expect(resultEl.classList.contains('plinko-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+5000 credits');
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderPlinkoResult(resultEl, {
            win: false, push: false, bucket: 4, multiplier: 0,
            net: -100, lossTribute: 10
        });

        expect(resultEl.innerHTML).toContain('+10 to jackpot');
    });
});
