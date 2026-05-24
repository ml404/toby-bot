const fs = require('fs');
const path = require('path');

// Every per-guild page renders a "← All servers" back-link to the
// matching `/{thing}/guilds` picker. Those pickers now auto-redirect
// to the user's anchored default server, which means a bare back-link
// gets silently bounced right back to the page the user just left —
// the user can never reach the picker to change their anchor.
//
// `?pick=true` on the picker URL bypasses the auto-redirect, so every
// back-link MUST carry it. This test enumerates the full per-guild
// template set so a new page added without the query param trips a
// single, named assertion instead of shipping the same bug a third
// time.

const TEMPLATES_DIR = path.resolve(
    __dirname,
    '../../main/resources/templates'
);

// Each entry: [template file (relative to templates dir), picker path
// that the back-link should target]. Grouped so the failure message
// points at the right family if someone breaks a whole category.
const CASES = [
    // Casino minigames (fixed in #486; regression guard).
    ['baccarat.html',          '/casino/guilds'],
    ['coinflip.html',          '/casino/guilds'],
    ['casinoholdem-solo.html', '/casino/guilds'],
    ['dice.html',              '/casino/guilds'],
    ['highlow.html',           '/casino/guilds'],
    ['horse-racing.html',      '/casino/guilds'],
    ['keno.html',              '/casino/guilds'],
    ['plinko.html',            '/casino/guilds'],
    ['roulette.html',          '/casino/guilds'],
    ['scratch.html',           '/casino/guilds'],
    ['slots.html',             '/casino/guilds'],
    ['wheel.html',             '/casino/guilds'],

    // Leaderboard.
    ['leaderboard.html',       '/leaderboards'],

    // Newly fixed by this change.
    ['poker-lobby.html',       '/poker/guilds'],
    ['blackjack-lobby.html',   '/blackjack/guilds'],
    ['blackjack-solo.html',    '/blackjack/guilds'],
    ['lottery.html',           '/lottery/guilds'],
    ['profile.html',           '/profile/guilds'],
    ['economy.html',           '/economy/guilds'],
    ['titles.html',            '/titles/guilds'],
    ['tip.html',               '/tip/guilds'],
    ['pvp.html',               '/pvp/guilds'],
    ['music-player.html',      '/music-player/guilds'],

    // Shared fragment used by every /moderation/{guildId}/... page.
    ['fragments/moderationHeader.html', '/moderation/guilds'],

    // Newly added back-links on pages that previously had none.
    ['intros.html',            '/intro/guilds'],
    ['excuses.html',           '/excuses/guilds'],
    ['teams.html',             '/teams/guilds'],
];

describe('per-guild back-link contract', () => {
    test.each(CASES)(
        '%s back-link targets %s with pick=true',
        (templateRel, pickerPath) => {
            const html = fs.readFileSync(
                path.join(TEMPLATES_DIR, templateRel),
                'utf8'
            );

            // Find the back-link anchor. Most templates use the
            // `back-link` class; music-player.html uses `btn-tertiary`
            // for visual reasons but the contract is the same. Inner
            // text varies a bit ("All servers", "All casino servers",
            // "All markets", "All leaderboards") so we don't pin it —
            // the contract is on the href.
            const anchorMatch = html.match(
                /<a[^>]*class="(?:back-link|btn-tertiary)"[^>]*>[\s\S]*?<\/a>/
            );
            expect(anchorMatch).not.toBeNull();
            const anchor = anchorMatch[0];

            // The href must point at the matching picker — both the
            // Thymeleaf form `@{/foo/guilds(...)}` and the literal
            // `href="/foo/guilds..."` are valid; either way the path
            // segment must match.
            const escapedPath = pickerPath.replace(/\//g, '\\/');
            expect(anchor).toMatch(
                new RegExp(`(?:@\\{|href=")${escapedPath}`)
            );

            // The query param must be present in one of the two forms.
            // `pick=true` — plain HTML query string.
            // `(pick=true)` — Thymeleaf URL-expression query syntax.
            expect(anchor).toMatch(/(?:\(pick=true\)|\?pick=true|&pick=true|&amp;pick=true)/);
        }
    );
});
