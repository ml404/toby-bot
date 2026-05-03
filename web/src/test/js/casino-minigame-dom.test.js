const { standardElements } = require('../../main/resources/static/js/casino-minigame-dom');

describe('TobyCasinoMinigameDom.standardElements', () => {
    afterEach(() => { document.body.innerHTML = ''; });

    test('returns null when no <main data-guild-id> is present', () => {
        document.body.innerHTML = '<div></div>';
        expect(standardElements('dice', 'roll')).toBeNull();
    });

    test('resolves the {prefix}-X element family from data attributes', () => {
        document.body.innerHTML =
            '<main data-guild-id="42" data-toby-coins="7" data-market-price="1.5">' +
            '<form id="dice-bet"></form>' +
            '<input id="dice-stake">' +
            '<button id="dice-roll"></button>' +
            '<button id="dice-roll-toby"></button>' +
            '<span id="dice-balance"></span>' +
            '<div id="dice-result"></div>' +
            '</main>';

        const els = standardElements('dice', 'roll');

        expect(els.guildId).toBe('42');
        expect(els.tobyCoins).toBe(7);
        expect(els.marketPrice).toBe(1.5);
        expect(els.form.id).toBe('dice-bet');
        expect(els.stakeInput.id).toBe('dice-stake');
        expect(els.primaryBtn.id).toBe('dice-roll');
        expect(els.tobyBtn.id).toBe('dice-roll-toby');
        expect(els.balanceEl.id).toBe('dice-balance');
        expect(els.resultEl.id).toBe('dice-result');
    });

    test('missing tobyCoins / marketPrice attrs default to 0', () => {
        document.body.innerHTML =
            '<main data-guild-id="1"></main>';

        const els = standardElements('coinflip', 'flip');

        expect(els.guildId).toBe('1');
        expect(els.tobyCoins).toBe(0);
        expect(els.marketPrice).toBe(0);
    });
});
