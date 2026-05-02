// Regression test for the split-layout cleanup bug. blackjack-solo.js
// flips `hidden` on `#bj-player-cards` (.bj-cards) and `#bj-player-hands`
// (.bj-seats) to switch between the single-hand and split layouts. The
// `.bj-cards { display: flex }` and `.bj-seats { display: grid }` author
// rules used to override the user-agent `[hidden] { display: none }` at
// equal specificity, leaving the previous round's split sub-hands on
// screen after a fresh deal. blackjack.css now carries an explicit
// `.bj-cards[hidden], .bj-seats[hidden] { display: none; }` override at
// specificity (0,2,0) to win the cascade. This test loads the real CSS
// into the jsdom document and confirms the override actually applies.
const fs = require('fs');
const path = require('path');

describe('blackjack.css — hidden actually hides .bj-cards and .bj-seats', () => {
    let cardsEl;
    let seatsEl;

    beforeEach(() => {
        const css = fs.readFileSync(
            path.join(__dirname, '../../main/resources/static/css/blackjack.css'),
            'utf8'
        );
        document.head.innerHTML = '';
        const style = document.createElement('style');
        style.textContent = css;
        document.head.appendChild(style);

        document.body.innerHTML = `
            <div id="cards" class="bj-cards"></div>
            <div id="seats" class="bj-seats"></div>
        `;
        cardsEl = document.getElementById('cards');
        seatsEl = document.getElementById('seats');
    });

    test('.bj-cards lays out as flex when visible', () => {
        expect(getComputedStyle(cardsEl).display).toBe('flex');
    });

    test('.bj-seats lays out as grid when visible', () => {
        expect(getComputedStyle(seatsEl).display).toBe('grid');
    });

    test('toggling hidden on .bj-cards collapses display to none (was the bug)', () => {
        cardsEl.hidden = true;
        expect(getComputedStyle(cardsEl).display).toBe('none');
    });

    test('toggling hidden on .bj-seats collapses display to none (was the bug)', () => {
        seatsEl.hidden = true;
        expect(getComputedStyle(seatsEl).display).toBe('none');
    });

    test('clearing hidden restores the layout display', () => {
        seatsEl.hidden = true;
        seatsEl.hidden = false;
        expect(getComputedStyle(seatsEl).display).toBe('grid');
    });
});
