// DOM unit test for the multi-tier side-pot renderer. Pulls the
// production JS in through the same window global the page uses.
require('../../main/resources/static/js/poker-pots');

describe('TobyPokerPots.render', () => {
    let container;

    beforeEach(() => {
        document.body.innerHTML = '<div id="pots"></div>';
        container = document.getElementById('pots');
    });

    test('no-op when containerEl is null', () => {
        expect(() => window.TobyPokerPots.render({
            containerEl: null,
            pots: [{ amount: 100, cap: 50, eligibleDiscordIds: [1], winners: [1], payoutByDiscordId: { '1': 100 } }],
        })).not.toThrow();
    });

    test('hides the container when no pots are provided', () => {
        container.hidden = false;
        window.TobyPokerPots.render({ containerEl: container, pots: [] });
        expect(container.hidden).toBe(true);
        expect(container.innerHTML).toBe('');
    });

    test('single pot is labelled simply "Pot" (no main/side distinction)', () => {
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [{
                amount: 95,
                cap: 100,
                eligibleDiscordIds: [1, 2],
                winners: [1],
                payoutByDiscordId: { '1': 95 },
            }],
        });
        const labels = container.querySelectorAll('.poker-pot-tier-label');
        expect(labels.length).toBe(1);
        expect(labels[0].textContent).toBe('Pot');
        expect(container.querySelector('.poker-pot-tier-amount').textContent).toBe('95');
    });

    test('multi-tier renders Main pot + Side pot N labels in order', () => {
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [
                { amount: 150, cap: 50, eligibleDiscordIds: [1, 2, 3], winners: [1], payoutByDiscordId: { '1': 150 } },
                { amount: 300, cap: 200, eligibleDiscordIds: [2, 3], winners: [2], payoutByDiscordId: { '2': 300 } },
            ],
        });
        const labels = Array.from(container.querySelectorAll('.poker-pot-tier-label'))
            .map(function (n) { return n.textContent; });
        expect(labels).toEqual(['Main pot', 'Side pot 1']);
        const amounts = Array.from(container.querySelectorAll('.poker-pot-tier-amount'))
            .map(function (n) { return n.textContent; });
        expect(amounts).toEqual(['150', '300']);
    });

    test('eligible-player set is rendered per tier', () => {
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [
                { amount: 200, cap: 50, eligibleDiscordIds: [1, 2, 3, 4], winners: [1], payoutByDiscordId: { '1': 200 } },
                { amount: 300, cap: 200, eligibleDiscordIds: [3, 4], winners: [3], payoutByDiscordId: { '3': 300 } },
            ],
        });
        const eligibleNodes = container.querySelectorAll('.poker-pot-tier-eligible');
        expect(eligibleNodes.length).toBe(2);
        expect(eligibleNodes[0].textContent).toContain('1, 2, 3, 4');
        expect(eligibleNodes[1].textContent).toContain('3, 4');
        expect(eligibleNodes[1].textContent).not.toContain('1');
    });

    test('chopped tier lists each winner', () => {
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [
                { amount: 150, cap: 50, eligibleDiscordIds: [1, 2, 3], winners: [1], payoutByDiscordId: { '1': 150 } },
                { amount: 300, cap: 200, eligibleDiscordIds: [2, 3], winners: [2, 3], payoutByDiscordId: { '2': 150, '3': 150 } },
            ],
        });
        const winnersNodes = container.querySelectorAll('.poker-pot-tier-winners');
        expect(winnersNodes[0].textContent).toContain('1');
        expect(winnersNodes[1].textContent).toContain('2, 3');
    });

    test('refundedByDiscordId is rendered as a footnote when present', () => {
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [{ amount: 100, cap: 50, eligibleDiscordIds: [1, 2], winners: [2], payoutByDiscordId: { '2': 100 } }],
            refundedByDiscordId: { '2': 150 },
        });
        const note = container.querySelector('.poker-pots-refunds');
        expect(note).not.toBeNull();
        expect(note.textContent).toContain('2');
        expect(note.textContent).toContain('150');
    });

    test('no refund footnote when the map is empty', () => {
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [{ amount: 100, cap: 50, eligibleDiscordIds: [1, 2], winners: [2], payoutByDiscordId: { '2': 100 } }],
            refundedByDiscordId: {},
        });
        expect(container.querySelector('.poker-pots-refunds')).toBeNull();
    });

    test('clears prior render before drawing the next one', () => {
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [
                { amount: 150, cap: 50, eligibleDiscordIds: [1, 2, 3], winners: [1], payoutByDiscordId: { '1': 150 } },
                { amount: 300, cap: 200, eligibleDiscordIds: [2, 3], winners: [2], payoutByDiscordId: { '2': 300 } },
            ],
        });
        // Second render with a single pot should drop the second tier from the DOM.
        window.TobyPokerPots.render({
            containerEl: container,
            pots: [
                { amount: 95, cap: 100, eligibleDiscordIds: [1, 2], winners: [1], payoutByDiscordId: { '1': 95 } },
            ],
        });
        expect(container.querySelectorAll('.poker-pot-tier').length).toBe(1);
        expect(container.querySelector('.poker-pot-tier-label').textContent).toBe('Pot');
    });
});
