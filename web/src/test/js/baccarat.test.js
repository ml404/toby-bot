const { renderBaccaratResult } = require('../../main/resources/static/js/baccarat');
require('../../main/resources/static/js/casino-jackpot');
require('../../main/resources/static/js/casino-result');
require('../../main/resources/static/js/casino-render');

describe('renderBaccaratResult', () => {
    let resultEl;
    let bankerCardsEl;
    let playerCardsEl;
    let bankerTotalEl;
    let playerTotalEl;
    let tableEl;

    beforeEach(() => {
        document.body.innerHTML = `
            <section id="t" hidden>
                <div id="bc"></div>
                <div id="pc"></div>
                <span id="bt"></span>
                <span id="pt"></span>
            </section>
            <div id="r"></div>
        `;
        resultEl = document.getElementById('r');
        bankerCardsEl = document.getElementById('bc');
        playerCardsEl = document.getElementById('pc');
        bankerTotalEl = document.getElementById('bt');
        playerTotalEl = document.getElementById('pt');
        tableEl = document.getElementById('t');
    });

    function renderWith(body) {
        renderBaccaratResult({
            resultEl, bankerCardsEl, playerCardsEl,
            bankerTotalEl, playerTotalEl, tableEl,
            // Keep these tests synchronous — staging is exercised by the
            // dedicated "staged path" test below.
            stagger: false,
            body,
        });
    }

    test('Player win shows the table, paints both hand totals, and the win class', () => {
        renderWith({
            win: true,
            push: false,
            side: 'PLAYER',
            winner: 'PLAYER',
            playerCards: ['5♠', '3♥'],
            bankerCards: ['2♣', '4♦'],
            playerTotal: 8,
            bankerTotal: 6,
            isPlayerNatural: true,
            isBankerNatural: false,
            multiplier: 2.0,
            net: 100,
        });

        expect(tableEl.hidden).toBe(false);
        expect(resultEl.classList.contains('bac-result-win')).toBe(true);
        expect(playerTotalEl.textContent).toBe('(8 • Natural)');
        expect(bankerTotalEl.textContent).toBe('(6)');
        expect(resultEl.innerHTML).toContain('Player wins');
        expect(resultEl.innerHTML).toContain('+100 credits');
        expect(resultEl.innerHTML).toContain('2.00×');
    });

    test('Banker win labels the 5% commission verdict', () => {
        renderWith({
            win: true,
            push: false,
            side: 'BANKER',
            winner: 'BANKER',
            playerCards: ['5♠', '4♥'],
            bankerCards: ['8♣', '7♦'],
            playerTotal: 9,
            bankerTotal: 5,
            isPlayerNatural: false,
            isBankerNatural: false,
            multiplier: 1.95,
            net: 95,
        });

        expect(resultEl.classList.contains('bac-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('Banker wins');
        expect(resultEl.innerHTML).toContain('5% commission');
        expect(resultEl.innerHTML).toContain('1.95×');
    });

    test('Tie win calls out the tie at 9.00x', () => {
        renderWith({
            win: true,
            push: false,
            side: 'TIE',
            winner: 'TIE',
            playerCards: ['5♠', '3♥'],
            bankerCards: ['4♣', '4♦'],
            playerTotal: 8,
            bankerTotal: 8,
            isPlayerNatural: true,
            isBankerNatural: true,
            multiplier: 9.0,
            net: 400,
        });

        expect(resultEl.classList.contains('bac-result-win')).toBe(true);
        expect(resultEl.innerHTML).toContain('Tie!');
        expect(resultEl.innerHTML).toContain('+400 credits');
        expect(resultEl.innerHTML).toContain('9.00×');
    });

    test('lose path names the winning side and the lost stake', () => {
        renderWith({
            win: false,
            push: false,
            side: 'PLAYER',
            winner: 'BANKER',
            playerCards: ['5♠', '2♥'],
            bankerCards: ['9♣', '8♦'],
            playerTotal: 7,
            bankerTotal: 7,
            isPlayerNatural: false,
            isBankerNatural: false,
            net: -50,
        });

        expect(resultEl.classList.contains('bac-result-lose')).toBe(true);
        expect(resultEl.innerHTML).toContain('Banker wins');
        expect(resultEl.innerHTML).toContain('Player');
        expect(resultEl.innerHTML).toContain('50 credits');
    });

    test('push (tied game on Player/Banker bet) refunds the stake and uses the push class', () => {
        renderWith({
            win: false,
            push: true,
            side: 'PLAYER',
            winner: 'TIE',
            playerCards: ['5♠', '2♥'],
            bankerCards: ['3♣', '4♦'],
            playerTotal: 7,
            bankerTotal: 7,
            isPlayerNatural: false,
            isBankerNatural: false,
            payout: 100,
            net: 0,
        });

        expect(resultEl.classList.contains('bac-result-push')).toBe(true);
        expect(resultEl.classList.contains('bac-result-win')).toBe(false);
        expect(resultEl.classList.contains('bac-result-lose')).toBe(false);
        expect(resultEl.innerHTML).toContain('Tie game');
        expect(resultEl.innerHTML).toContain('refunded');
        expect(resultEl.innerHTML).toContain('100 credits');
    });

    test('jackpot win prepends the JACKPOT banner', () => {
        renderWith({
            win: true,
            push: false,
            side: 'PLAYER',
            winner: 'PLAYER',
            playerCards: ['5♠', '3♥'],
            bankerCards: ['2♣', '4♦'],
            playerTotal: 8,
            bankerTotal: 6,
            isPlayerNatural: true,
            isBankerNatural: false,
            multiplier: 2.0,
            net: 100,
            jackpotPayout: 999,
        });

        expect(resultEl.classList.contains('bac-result-jackpot')).toBe(true);
        expect(resultEl.innerHTML).toContain('+999 credits');
    });

    test('lose with lossTribute appends "+N to jackpot" suffix', () => {
        renderWith({
            win: false,
            push: false,
            side: 'PLAYER',
            winner: 'BANKER',
            playerCards: ['5♠', '2♥'],
            bankerCards: ['9♣', '8♦'],
            playerTotal: 7,
            bankerTotal: 7,
            isPlayerNatural: false,
            isBankerNatural: false,
            net: -50,
            lossTribute: 5,
        });

        expect(resultEl.innerHTML).toContain('+5 to jackpot');
    });

    test('Natural badge is omitted when neither side has 8 or 9 on first two cards', () => {
        renderWith({
            win: true,
            push: false,
            side: 'PLAYER',
            winner: 'PLAYER',
            playerCards: ['5♠', '2♥'],
            bankerCards: ['3♣', '3♦'],
            playerTotal: 7,
            bankerTotal: 6,
            isPlayerNatural: false,
            isBankerNatural: false,
            multiplier: 2.0,
            net: 100,
        });

        expect(playerTotalEl.textContent).toBe('(7)');
        expect(bankerTotalEl.textContent).toBe('(6)');
    });

    test('returns early on missing result element', () => {
        expect(() => renderBaccaratResult({
            resultEl: null, bankerCardsEl: null, playerCardsEl: null,
            tableEl: null, body: { win: true }
        })).not.toThrow();
    });

    test('win flashes a chip stack on the flash target; loss leaves it untouched', () => {
        renderBaccaratResult({
            resultEl, bankerCardsEl, playerCardsEl,
            bankerTotalEl, playerTotalEl, tableEl,
            flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: true, push: false, side: 'PLAYER', winner: 'PLAYER',
                playerCards: ['5♠', '3♥'], bankerCards: ['2♣', '4♦'],
                playerTotal: 8, bankerTotal: 6, multiplier: 2.0, net: 100,
            },
        });
        expect(tableEl.querySelector('.casino-chip-stack')).not.toBeNull();
        expect(tableEl.querySelector('.casino-chip-payout').textContent).toBe('+100');

        // Reset the felt for the loss check.
        tableEl.querySelectorAll('.casino-chip-stack').forEach((el) => el.remove());
        renderBaccaratResult({
            resultEl, bankerCardsEl, playerCardsEl,
            bankerTotalEl, playerTotalEl, tableEl,
            flashTargetEl: tableEl,
            stagger: false,
            body: {
                win: false, push: false, side: 'PLAYER', winner: 'BANKER',
                playerCards: ['5♠', '2♥'], bankerCards: ['9♣', '8♦'],
                playerTotal: 7, bankerTotal: 7, net: -50,
            },
        });
        expect(tableEl.querySelector('.casino-chip-stack')).toBeNull();
    });

    test('staged path holds the result line until the deal sequence completes', () => {
        jest.useFakeTimers();
        try {
            renderBaccaratResult({
                resultEl, bankerCardsEl, playerCardsEl,
                bankerTotalEl, playerTotalEl, tableEl,
                flashTargetEl: tableEl,
                stagger: true,
                dealMs: 50,
                body: {
                    win: true, push: false, side: 'PLAYER', winner: 'PLAYER',
                    playerCards: ['5♠', '3♥'], bankerCards: ['2♣', '4♦'],
                    playerTotal: 8, bankerTotal: 6,
                    isPlayerNatural: true, isBankerNatural: false,
                    multiplier: 2.0, net: 100,
                },
            });

            // Synchronously after the call: cards haven't been dealt yet
            // (only the t=0 setTimeout is queued), result line is empty,
            // totals are blanked while the deal plays out.
            expect(resultEl.classList.contains('bac-result-win')).toBe(false);
            expect(resultEl.innerHTML).toBe('');
            expect(playerTotalEl.textContent).toBe('');
            expect(bankerTotalEl.textContent).toBe('');
            expect(playerCardsEl.querySelectorAll('.casino-card-glyph').length).toBe(0);
            expect(bankerCardsEl.querySelectorAll('.casino-card-glyph').length).toBe(0);

            // Halfway through the first interval — only Player's first card.
            jest.advanceTimersByTime(25);
            expect(playerCardsEl.querySelectorAll('.casino-card-glyph').length).toBe(1);
            expect(bankerCardsEl.querySelectorAll('.casino-card-glyph').length).toBe(0);
            expect(resultEl.classList.contains('bac-result-win')).toBe(false);

            // Advance past the deal sequence (4 * 50ms = 200ms) but stop
            // before flashChipsOn's 2.5s safety-cleanup timer fires —
            // otherwise runAllTimers would remove the chip stack we want
            // to assert.
            jest.advanceTimersByTime(250);
            expect(playerCardsEl.querySelectorAll('.casino-card-glyph').length).toBe(2);
            expect(bankerCardsEl.querySelectorAll('.casino-card-glyph').length).toBe(2);
            expect(resultEl.classList.contains('bac-result-win')).toBe(true);
            expect(resultEl.innerHTML).toContain('Player wins');
            expect(playerTotalEl.textContent).toBe('(8 • Natural)');
            expect(bankerTotalEl.textContent).toBe('(6)');
            expect(tableEl.querySelector('.casino-chip-stack')).not.toBeNull();
        } finally {
            jest.useRealTimers();
        }
    });
});
