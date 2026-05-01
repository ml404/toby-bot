// Multi-tier side-pot rendering helper. Used by poker-table.js to
// render PokerWebService.HandResultView.pots after a hand resolves.
//
// Exposed as window.TobyPokerPots so the same module can be required
// from Jest (jsdom) tests without bundler magic — same convention as
// the casino-result.js helper.
(function (root) {
    'use strict';

    function asString(id) { return String(id); }

    function tierLabel(index, total) {
        if (total <= 1) return 'Pot';
        if (index === 0) return 'Main pot';
        return 'Side pot ' + index;
    }

    function renderTier(tier, index, total) {
        const li = document.createElement('li');
        li.className = 'poker-pot-tier';

        const label = document.createElement('span');
        label.className = 'poker-pot-tier-label';
        label.textContent = tierLabel(index, total);
        li.appendChild(label);

        const amount = document.createElement('span');
        amount.className = 'poker-pot-tier-amount';
        amount.textContent = String(tier.amount);
        li.appendChild(amount);

        const eligibleIds = (tier.eligibleDiscordIds || []).map(asString);
        if (eligibleIds.length) {
            const eligible = document.createElement('span');
            eligible.className = 'poker-pot-tier-eligible';
            eligible.textContent = 'eligible: ' + eligibleIds.join(', ');
            li.appendChild(eligible);
        }

        const winnerIds = (tier.winners || []).map(asString);
        if (winnerIds.length) {
            const winners = document.createElement('span');
            winners.className = 'poker-pot-tier-winners';
            winners.textContent = 'won by: ' + winnerIds.join(', ');
            li.appendChild(winners);
        }
        return li;
    }

    function render(opts) {
        const containerEl = opts && opts.containerEl;
        if (!containerEl) return;
        const pots = (opts.pots || []).slice();
        containerEl.innerHTML = '';
        if (pots.length === 0) {
            containerEl.hidden = true;
            return;
        }
        containerEl.hidden = false;

        const list = document.createElement('ul');
        list.className = 'poker-pots';
        pots.forEach(function (tier, idx) {
            list.appendChild(renderTier(tier, idx, pots.length));
        });
        containerEl.appendChild(list);

        const refunds = opts.refundedByDiscordId || {};
        const refundIds = Object.keys(refunds);
        if (refundIds.length) {
            const note = document.createElement('p');
            note.className = 'poker-pots-refunds';
            note.textContent = 'Refunded: ' + refundIds
                .map(function (id) { return id + ' (' + refunds[id] + ')'; })
                .join(', ');
            containerEl.appendChild(note);
        }
    }

    root.TobyPokerPots = { render: render };
})(typeof window !== 'undefined' ? window : globalThis);
