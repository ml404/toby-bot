package web.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import web.util.displayName

/**
 * Public changelog page. Hand-curated highlights — kept here as a static
 * list rather than scraped from `git log` because the curated framing
 * ("here's what's worth knowing") is the point. New entries go at the
 * top of [ENTRIES]. Older entries can stay until they fall off the
 * bottom or be pruned in batches; the goal isn't a full history (git
 * already has that), it's a pitch surface that shows the project is
 * alive.
 *
 * Public (no auth) so recruiters and prospective installers can scan
 * the recent activity without signing in. Endpoint matches the route
 * permitAll-listed in [web.configuration.WebSecurityConfig].
 */
@Controller
class ChangelogController {

    @GetMapping("/changelog")
    fun changelog(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model,
    ): String {
        model.addAttribute("entries", ENTRIES)
        if (user != null) model.addAttribute("username", user.displayName())
        return "changelog"
    }

    /**
     * One row in the changelog timeline.
     *
     * @param date  human-readable, e.g. "May 2026". Sort order is the
     *              position in the [ENTRIES] list — newest first.
     * @param title short headline.
     * @param summary 1–2 sentences. Plain text, no markdown.
     * @param emoji optional leading glyph; null hides the icon column.
     * @param prNumber optional GitHub PR number; renders as a small link.
     */
    data class ChangelogEntry(
        val date: String,
        val title: String,
        val summary: String,
        val emoji: String? = null,
        val prNumber: Int? = null,
    )

    companion object {
        private const val REPO_URL = "https://github.com/ml404/toby-bot"

        // Newest first. Cap to ~15 entries; older highlights can be
        // pruned when they roll off the bottom.
        val ENTRIES: List<ChangelogEntry> = listOf(
            ChangelogEntry(
                date = "May 2026",
                title = "Moderation page split + searchable settings",
                summary = "The single moderation page became six focused sub-pages " +
                    "(Users, Settings, Voice, Poll, Casino, Lottery). The Settings " +
                    "page got a sticky search box and a dedicated Jackpot section " +
                    "exposing five eligibility/payout configs that were previously " +
                    "backend-only. A new CI guard fails the build if a future config " +
                    "key is added without a UI row.",
                emoji = "🧹",
                prNumber = 424,
            ),
            ChangelogEntry(
                date = "May 2026",
                title = "Jackpot RTP eligibility gate",
                summary = "High-RTP games (blackjack, coinflip, baccarat, roulette) " +
                    "can now be excluded from jackpot rolls via a configurable RTP " +
                    "ceiling, so they don't double-dip on a sweetener they don't need.",
                emoji = "🎰",
                prNumber = 422,
            ),
            ChangelogEntry(
                date = "May 2026",
                title = "Anti-autoclicker channel logs",
                summary = "Suspected-bot session embeds now post to a configurable " +
                    "Discord channel and update in place as forced-loss substitutions " +
                    "accumulate over the session.",
                emoji = "🔍",
                prNumber = 420,
            ),
            ChangelogEntry(
                date = "April 2026",
                title = "Daily lottery (Pick 5 of 49 + weighted mode)",
                summary = "Auto-runs at 00:00 UTC: closes the prior draw, pays " +
                    "tier-based prizes, opens a fresh one seeded from the jackpot " +
                    "pool. NUMBER_MATCH for high-engagement servers, WEIGHTED for " +
                    "low-traffic ones; configurable per guild from the moderation " +
                    "Lottery sub-page.",
                emoji = "🎟️",
                prNumber = 412,
            ),
            ChangelogEntry(
                date = "April 2026",
                title = "Casino refresh: Roulette + Casino Hold'em",
                summary = "Two new minigames join the quick-play roster, both " +
                    "wired into the per-guild jackpot pool with the same loss-tribute " +
                    "and stake-anchor rules as the rest.",
                emoji = "🎲",
                prNumber = 404,
            ),
            ChangelogEntry(
                date = "April 2026",
                title = "Multiplayer Blackjack tables",
                summary = "Up to 7 seats per multi-table, configurable shot clock, " +
                    "S17/H17 dealer rule, and 3:2 / 6:5 payout toggle. Solo blackjack " +
                    "shares the same stake bounds.",
                emoji = "♠️",
            ),
            ChangelogEntry(
                date = "March 2026",
                title = "Poker tables (fixed-limit Hold'em)",
                summary = "2–9 seats, configurable blinds and bet structure, " +
                    "per-actor shot clock with auto-fold, and rake routed to the " +
                    "jackpot pool.",
                emoji = "♠️",
            ),
            ChangelogEntry(
                date = "March 2026",
                title = "TOBY coin live market",
                summary = "Per-server live market with a 5-minute price tick. Earn " +
                    "social credit by being active, then trade it for TOBY coin.",
                emoji = "💰",
            ),
            ChangelogEntry(
                date = "February 2026",
                title = "Web moderation admin",
                summary = "Browser-based moderation: toggle user permissions, " +
                    "adjust social credit, mute or move voice channels, post polls — " +
                    "no slash commands needed.",
                emoji = "🛡️",
            ),
        )

        fun repoUrl(): String = REPO_URL
    }
}
