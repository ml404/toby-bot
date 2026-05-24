package web.template

import database.dto.guild.ConfigDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import common.casino.coinflip.Coinflip
import common.casino.dice.Dice

/**
 * Presence regression for the per-game config rows on the moderation
 * page. The original miss was that every `BLACKJACK_*` key existed in
 * [ConfigDto.Configurations] and the `POST /moderation/{guildId}/config`
 * endpoint validated and persisted them, but the moderation HTML had no
 * `<input>` row to actually drive that POST — admins had to curl. The
 * same class of miss left every `JACKPOT_*` eligibility key invisible
 * for months.
 *
 * Reads `templates/moderation/settings.html` (and the sibling lottery
 * template, which carries lottery rows) off the classpath and asserts
 * each config key appears as a `data-key` attribute, which is what the
 * page's save-config JS keys off. The bidirectional enum-vs-template
 * guard at the bottom of the file catches the broader class of miss
 * — adding a new enum case without a UI row will fail CI.
 */
class ModerationTemplateRowsTest {

    private val settingsHtml: String by lazy { readTemplate("templates/moderation/settings.html") }
    private val lotteryHtml: String by lazy { readTemplate("templates/moderation/lottery.html") }

    private fun readTemplate(path: String): String {
        val url = javaClass.classLoader.getResource(path)
        assertNotNull(url, "$path must be on the classpath")
        return url!!.readText()
    }

    @Test
    fun `settings template surfaces every blackjack config key as an editable row`() {
        // All Blackjack keys (table rules + ante stakes) now live together
        // inside the Blackjack section; this test just asserts
        // presence-anywhere, not which card.
        val keys = listOf(
            ConfigDto.Configurations.BLACKJACK_RAKE_PCT,
            ConfigDto.Configurations.BLACKJACK_MIN_ANTE,
            ConfigDto.Configurations.BLACKJACK_MAX_ANTE,
            ConfigDto.Configurations.BLACKJACK_MAX_SEATS,
            ConfigDto.Configurations.BLACKJACK_SHOT_CLOCK_SECONDS,
            ConfigDto.Configurations.BLACKJACK_DEALER_HITS_SOFT_17,
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_NUM,
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_DEN,
        )
        for (key in keys) {
            assertTrue(
                settingsHtml.contains("data-key=\"${key.name}\""),
                "settings.html should have a config-row for ${key.name} so admins can edit it from the UI"
            )
        }
    }

    @Test
    fun `settings template surfaces every poker config key as an editable row`() {
        // Same regression class as blackjack — `POKER_RAKE_PCT` was the only
        // poker key with a UI row before; the per-table parameters
        // (blinds, bets, buy-in range, max seats, shot clock) were defined
        // and validated server-side but admins had no form for them.
        val keys = listOf(
            ConfigDto.Configurations.POKER_RAKE_PCT,
            ConfigDto.Configurations.POKER_SMALL_BLIND,
            ConfigDto.Configurations.POKER_BIG_BLIND,
            ConfigDto.Configurations.POKER_SMALL_BET,
            ConfigDto.Configurations.POKER_BIG_BET,
            ConfigDto.Configurations.POKER_MIN_BUY_IN,
            ConfigDto.Configurations.POKER_MAX_BUY_IN,
            ConfigDto.Configurations.POKER_MAX_SEATS,
            ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS,
        )
        for (key in keys) {
            assertTrue(
                settingsHtml.contains("data-key=\"${key.name}\""),
                "settings.html should have a config-row for ${key.name} so admins can edit it from the UI"
            )
        }
    }

    @Test
    fun `blackjack section in settings template has its own collapsed details block`() {
        // The page wraps each game's keys in a `<details><summary>Game</summary>`
        // block so admins can scan to the right section. Without this wrapper
        // the keys would be lumped into "Economy" alongside trade fees and
        // lose discoverability.
        assertTrue(
            settingsHtml.contains("<summary>Blackjack</summary>"),
            "expected a <details><summary>Blackjack</summary> wrapper around the blackjack rows"
        )
    }

    @Test
    fun `poker section in settings template has its own collapsed details block`() {
        assertTrue(
            settingsHtml.contains("<summary>Poker</summary>"),
            "expected a <details><summary>Poker</summary> wrapper around the poker rows"
        )
    }

    @Test
    fun `settings template surfaces every stake or buy-in config key as an editable row`() {
        // Every stake-shaped config — per-minigame min/max, blackjack ante
        // bounds, and the poker chip thresholds (blinds/bets/buy-ins) —
        // has a row somewhere on the settings page. Blackjack ante and
        // Poker blinds/buy-ins now live inside their respective game
        // sections; the simpler games live in the "Game stake limits"
        // section. The jackpot stake anchor lives in the dedicated
        // Jackpot section; it's covered by the dedicated jackpot test
        // below.
        val keys = listOf(
            ConfigDto.Configurations.DICE_MIN_STAKE,
            ConfigDto.Configurations.DICE_MAX_STAKE,
            ConfigDto.Configurations.COINFLIP_MIN_STAKE,
            ConfigDto.Configurations.COINFLIP_MAX_STAKE,
            ConfigDto.Configurations.SLOTS_MIN_STAKE,
            ConfigDto.Configurations.SLOTS_MAX_STAKE,
            ConfigDto.Configurations.HIGHLOW_MIN_STAKE,
            ConfigDto.Configurations.HIGHLOW_MAX_STAKE,
            ConfigDto.Configurations.BACCARAT_MIN_STAKE,
            ConfigDto.Configurations.BACCARAT_MAX_STAKE,
            ConfigDto.Configurations.KENO_MIN_STAKE,
            ConfigDto.Configurations.KENO_MAX_STAKE,
            ConfigDto.Configurations.SCRATCH_MIN_STAKE,
            ConfigDto.Configurations.SCRATCH_MAX_STAKE,
            ConfigDto.Configurations.HOLDEM_MIN_STAKE,
            ConfigDto.Configurations.HOLDEM_MAX_STAKE,
            ConfigDto.Configurations.DUEL_MIN_STAKE,
            ConfigDto.Configurations.DUEL_MAX_STAKE,
            ConfigDto.Configurations.RPS_MIN_STAKE,
            ConfigDto.Configurations.RPS_MAX_STAKE,
            ConfigDto.Configurations.TICTACTOE_MIN_STAKE,
            ConfigDto.Configurations.TICTACTOE_MAX_STAKE,
            ConfigDto.Configurations.CONNECT4_MIN_STAKE,
            ConfigDto.Configurations.CONNECT4_MAX_STAKE,
            ConfigDto.Configurations.BLACKJACK_MIN_ANTE,
            ConfigDto.Configurations.BLACKJACK_MAX_ANTE,
            ConfigDto.Configurations.POKER_SMALL_BLIND,
            ConfigDto.Configurations.POKER_BIG_BLIND,
            ConfigDto.Configurations.POKER_SMALL_BET,
            ConfigDto.Configurations.POKER_BIG_BET,
            ConfigDto.Configurations.POKER_MIN_BUY_IN,
            ConfigDto.Configurations.POKER_MAX_BUY_IN,
        )
        for (key in keys) {
            assertTrue(
                settingsHtml.contains("data-key=\"${key.name}\""),
                "settings.html should have a config-row for ${key.name}"
            )
        }
    }

    @Test
    fun `game stake limits section has its own collapsed details block`() {
        assertTrue(
            settingsHtml.contains("<summary>Game stake limits</summary>"),
            "expected a <details><summary>Game stake limits</summary> wrapper around the simple-game stake rows"
        )
    }

    @Test
    fun `game stake limits table labels every game row with its name`() {
        // Stake limits live in a table so each game's min + max sit side by
        // side instead of stacked. Admins still scan by game — the row
        // header replaces the old h4 sub-grouping. Sample three games
        // (a simple one, a chance game, and Duel) so a flatten regression
        // or accidentally-dropped row trips a single assertion.
        // Blackjack and Poker rows live in their dedicated game sections
        // under "Stakes per hand" / "Blinds & buy-ins" subgroups.
        val rowHeaders = listOf(
            "<th scope=\"row\">Dice</th>",
            "<th scope=\"row\">Coinflip</th>",
            "<th scope=\"row\">Duel</th>",
        )
        for (h in rowHeaders) {
            assertTrue(
                settingsHtml.contains(h),
                "expected $h row inside the Game stake limits table"
            )
        }
    }

    @Test
    fun `blackjack section consolidates table rules and stake bounds in one place`() {
        // Pre-pass, BLACKJACK_MIN_ANTE / MAX_ANTE lived in a separate
        // "Casino stakes & buy-ins" card; tuning blackjack meant scrolling
        // between two sections. Pin them inside the Blackjack section.
        val blackjackStart = settingsHtml.indexOf("<summary>Blackjack</summary>")
        assertTrue(blackjackStart >= 0, "Blackjack section not found")
        val sectionEnd = settingsHtml.indexOf("</details>", blackjackStart)
        assertTrue(sectionEnd > blackjackStart, "Blackjack section not terminated")
        val sectionHtml = settingsHtml.substring(blackjackStart, sectionEnd)
        for (key in listOf("BLACKJACK_MIN_ANTE", "BLACKJACK_MAX_ANTE")) {
            assertTrue(
                sectionHtml.contains("data-key=\"$key\""),
                "$key should live inside the Blackjack section, not the Game stake limits card"
            )
        }
    }

    @Test
    fun `poker section consolidates table rules with blinds and buy-ins in one place`() {
        // Same regression-shape as the blackjack consolidation —
        // POKER_SMALL_BLIND etc. used to live in the stakes mega-card.
        val pokerStart = settingsHtml.indexOf("<summary>Poker</summary>")
        assertTrue(pokerStart >= 0, "Poker section not found")
        val sectionEnd = settingsHtml.indexOf("</details>", pokerStart)
        assertTrue(sectionEnd > pokerStart, "Poker section not terminated")
        val sectionHtml = settingsHtml.substring(pokerStart, sectionEnd)
        val pokerStakeKeys = listOf(
            "POKER_SMALL_BLIND",
            "POKER_BIG_BLIND",
            "POKER_SMALL_BET",
            "POKER_BIG_BET",
            "POKER_MIN_BUY_IN",
            "POKER_MAX_BUY_IN",
        )
        for (key in pokerStakeKeys) {
            assertTrue(
                sectionHtml.contains("data-key=\"$key\""),
                "$key should live inside the Poker section, not the Game stake limits card"
            )
        }
    }

    @Test
    fun `settings template groups sections under server economy and casino pillars`() {
        // The three top-level <h2 class="config-pillar"> headers anchor
        // the visual grouping of the otherwise-flat accordion sections.
        // If the pillars disappear, the page reverts to a wall of
        // accordions and the rearrangement loses its point.
        for (pillar in listOf("Server", "Economy", "Casino")) {
            assertTrue(
                settingsHtml.contains("<h2 class=\"config-pillar\">$pillar</h2>"),
                "expected a <h2 class=\"config-pillar\">$pillar</h2> grouping header"
            )
        }
    }

    @Test
    fun `every percent config row carries a visible suffix marker`() {
        // Admins editing a percent config used to see a bare number input
        // with no unit indicator. Each *_PCT row now wraps the input in
        // a `.config-input-group` with a trailing `<span class="config-suffix">%</span>`
        // so the unit is obvious without reading the label. This test
        // pins the wiring for every percent key — losing the suffix
        // (e.g. someone removing the wrapper during a refactor) means
        // admins lose the visual cue again.
        val percentKeys = listOf(
            ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT,
            ConfigDto.Configurations.JACKPOT_WIN_PCT,
            ConfigDto.Configurations.JACKPOT_RTP_MAX_PCT,
            ConfigDto.Configurations.TRADE_BUY_FEE_PCT,
            ConfigDto.Configurations.TRADE_SELL_FEE_PCT,
            ConfigDto.Configurations.POKER_RAKE_PCT,
            ConfigDto.Configurations.BLACKJACK_RAKE_PCT,
        )
        for (key in percentKeys) {
            // Slice the document at the `data-key="..."` row marker and
            // look forward to the closing `</div>` of that row. The
            // suffix span must appear inside that range.
            val marker = "data-key=\"${key.name}\""
            val rowStart = settingsHtml.indexOf(marker)
            assertTrue(rowStart >= 0, "row for ${key.name} not found")
            val rowEnd = settingsHtml.indexOf("</div>", rowStart)
            assertTrue(rowEnd > rowStart, "row for ${key.name} not terminated")
            val rowHtml = settingsHtml.substring(rowStart, rowEnd)
            assertTrue(
                rowHtml.contains("config-suffix") && rowHtml.contains(">%<"),
                "row for ${key.name} should include a `<span class=\"config-suffix\">%</span>` marker"
            )
        }
    }

    @Test
    fun `settings template has a dedicated Jackpot section with every jackpot tuning key`() {
        // The "jackpot max pct" the user couldn't find was JACKPOT_RTP_MAX_PCT,
        // defined in the enum but with no UI surface. Five jackpot eligibility
        // / payout keys are now grouped in a single <details><summary>Jackpot</summary>
        // section so admins find them all in one place.
        assertTrue(
            settingsHtml.contains("<summary>Jackpot</summary>"),
            "expected a <details><summary>Jackpot</summary> wrapper grouping the jackpot tuning keys"
        )
        val jackpotKeys = listOf(
            ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT,
            ConfigDto.Configurations.JACKPOT_WIN_PCT,
            ConfigDto.Configurations.JACKPOT_WHEEL_SEGMENTS,
            ConfigDto.Configurations.JACKPOT_RTP_MAX_PCT,
            ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS,
            ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS,
            ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS,
            ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR,
        )
        for (key in jackpotKeys) {
            assertTrue(
                settingsHtml.contains("data-key=\"${key.name}\""),
                "settings.html should have a config-row for ${key.name} in the Jackpot section"
            )
        }
    }

    @Test
    fun `settings template carries a config search box`() {
        // The search box is the discoverability fix that pairs with the
        // section reorganisation — admins can type "rtp" or
        // "JACKPOT_RTP_MAX_PCT" and the matching row surfaces. The input
        // id is what settings.js binds to.
        assertTrue(
            settingsHtml.contains("id=\"config-search\""),
            "settings.html should expose a #config-search input for the live filter"
        )
    }

    @Test
    fun `lottery template surfaces every participation incentive tier as an editable row`() {
        // Same regression class as the blackjack / poker / jackpot
        // groups above: the 18 incentive keys (3 tiers × 2 fields ×
        // 3 levers) were added to ConfigDto.Configurations to drive
        // the new "Participation incentives" UI, and the moderation
        // page must carry a `<input>` row for each so admins can edit
        // them. The bidirectional guard below picks up missing rows
        // too, but this presence check pins the wiring per-key with a
        // clearer failure message.
        val keys = listOf(
            ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY,
            ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS,
            ConfigDto.Configurations.LOTTERY_BULK_TIER2_BUY,
            ConfigDto.Configurations.LOTTERY_BULK_TIER2_BONUS,
            ConfigDto.Configurations.LOTTERY_BULK_TIER3_BUY,
            ConfigDto.Configurations.LOTTERY_BULK_TIER3_BONUS,
            ConfigDto.Configurations.LOTTERY_MULT_TIER1_TOTAL,
            ConfigDto.Configurations.LOTTERY_MULT_TIER1_BP,
            ConfigDto.Configurations.LOTTERY_MULT_TIER2_TOTAL,
            ConfigDto.Configurations.LOTTERY_MULT_TIER2_BP,
            ConfigDto.Configurations.LOTTERY_MULT_TIER3_TOTAL,
            ConfigDto.Configurations.LOTTERY_MULT_TIER3_BP,
            ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS,
            ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT,
            ConfigDto.Configurations.LOTTERY_MILESTONE2_TICKETS,
            ConfigDto.Configurations.LOTTERY_MILESTONE2_PCT,
            ConfigDto.Configurations.LOTTERY_MILESTONE3_TICKETS,
            ConfigDto.Configurations.LOTTERY_MILESTONE3_PCT,
        )
        for (key in keys) {
            assertTrue(
                lotteryHtml.contains("data-key=\"${key.name}\""),
                "lottery.html should have a config-row for ${key.name} in the Participation incentives section"
            )
        }
    }

    @Test
    fun `lottery template has a participation incentives details block carrying all three levers`() {
        // The section sits in its own collapsible block, with three
        // nested `<details>` levers (bulk / multiplier / milestone)
        // for visual separation. We scope to the outer block by
        // walking `<details>` / `</details>` pairs from the section's
        // own opening tag, since a naive "next </details>" would
        // terminate at the first nested closer.
        val summaryIdx = lotteryHtml.indexOf("Participation incentives")
        assertTrue(summaryIdx >= 0, "expected a Participation incentives section header")
        // Walk back to the outer `<details` tag that wraps the summary.
        val outerOpen = lotteryHtml.lastIndexOf("<details", summaryIdx)
        assertTrue(outerOpen >= 0, "expected an outer <details> wrapping the Participation incentives summary")
        val outerEnd = matchingCloseDetails(lotteryHtml, outerOpen)
        assertTrue(outerEnd > outerOpen, "outer Participation incentives <details> not terminated")
        val sectionHtml = lotteryHtml.substring(outerOpen, outerEnd)
        for (heading in listOf(
            "Bulk bonus tickets",
            "Volume weight multiplier",
            "Pool-growth milestones",
        )) {
            assertTrue(
                sectionHtml.contains(heading),
                "expected '$heading' subhead inside the Participation incentives section",
            )
        }
        // All 18 tier inputs must live inside this section (not
        // scattered across the page).
        for (key in listOf(
            "LOTTERY_BULK_TIER1_BUY",
            "LOTTERY_MULT_TIER1_BP",
            "LOTTERY_MILESTONE1_PCT",
        )) {
            assertTrue(
                sectionHtml.contains("data-key=\"$key\""),
                "$key should live inside the Participation incentives section",
            )
        }
    }

    /**
     * Find the `</details>` index that closes the `<details` opened
     * at [openIdx], honouring nested `<details>` blocks. Returns -1
     * if the tag is never closed (shouldn't happen on a well-formed
     * template). Used by section-scoped assertions where a naive
     * `indexOf("</details>", start)` would stop at the first nested
     * closer instead of the matching outer one.
     */
    private fun matchingCloseDetails(html: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < html.length) {
            val nextOpen = html.indexOf("<details", i)
            val nextClose = html.indexOf("</details>", i)
            if (nextClose < 0) return -1
            if (nextOpen in 0..<nextClose) {
                depth++
                i = nextOpen + "<details".length
            } else {
                depth--
                if (depth == 0) return nextClose
                i = nextClose + "</details>".length
            }
        }
        return -1
    }

    @Test
    fun `lottery template renders active rules summary backed by overview lotteryIncentives`() {
        // The "Active rules" lines (one per lever) read the resolved
        // tier projection off `overview.lotteryIncentives`. If the
        // backing field is renamed or removed, every active-rules
        // expression in the template fails at page render. Pin the
        // three Thymeleaf expressions so a backend rename forces a
        // matching template update.
        for (expr in listOf(
            "overview.lotteryIncentives.bulkTiers",
            "overview.lotteryIncentives.multiplierTiers",
            "overview.lotteryIncentives.poolMilestones",
        )) {
            assertTrue(
                lotteryHtml.contains(expr),
                "lottery.html should reference $expr to render the Active rules summary",
            )
        }
    }

    /**
     * Bidirectional guard: every [ConfigDto.Configurations] enum case
     * (minus an explicit allowlist of internal keys) must appear as a
     * `data-key="..."` somewhere under `templates/moderation/`, AND
     * every `data-key` in those templates must reference a real enum
     * case. The first half catches the exact failure mode that left
     * `JACKPOT_RTP_MAX_PCT` invisible — adding a new enum case without
     * a UI row fails CI. The second half catches typos and stale rows
     * (e.g. an enum case is renamed but the template row is missed).
     */
    @Test
    fun `every config key has a UI surface and every UI row references a real key`() {
        // Internal-only keys that intentionally don't have a UI surface.
        // ACTIVITY_TRACKING_NOTIFIED is an internal one-shot flag set
        // by the bot when it DMs the owner about activity tracking;
        // there's no admin-facing reason to toggle it from the web UI.
        // INSTALL_MODE / INSTALLED_AT are sentinels written by the
        // in-Discord /install wizard — owners interact with that flow
        // directly, never through the moderation web UI.
        val internalOnly = setOf(
            ConfigDto.Configurations.ACTIVITY_TRACKING_NOTIFIED,
            ConfigDto.Configurations.INSTALL_MODE,
            ConfigDto.Configurations.INSTALLED_AT,
        )

        val templates = listOf(
            readTemplate("templates/moderation/users.html"),
            readTemplate("templates/moderation/settings.html"),
            readTemplate("templates/moderation/voice.html"),
            readTemplate("templates/moderation/poll.html"),
            readTemplate("templates/moderation/casino.html"),
            readTemplate("templates/moderation/lottery.html"),
            readTemplate("templates/moderation/leveling.html"),
            readTemplate("templates/moderation/welcome.html"),
        )
        val combined = templates.joinToString("\n")

        // Forward direction: every enum value (except internal-only)
        // surfaces somewhere in the moderation templates.
        val unsurfaced = ConfigDto.Configurations.entries
            .filter { it !in internalOnly }
            .filter { !combined.contains("data-key=\"${it.name}\"") }
        assertTrue(
            unsurfaced.isEmpty(),
            "These ConfigDto.Configurations enum values exist in the backend but " +
                "have no UI row in templates/moderation/. Add a `<div class=\"config-row\" " +
                "data-key=\"…\">` for each, or extend the internalOnly allowlist if " +
                "the key is genuinely never user-tuned: ${unsurfaced.map { it.name }}"
        )

        // Reverse direction: every `data-key="..."` in the templates
        // names a real enum value. Catches typos and orphaned rows.
        val keyNames = ConfigDto.Configurations.entries.map { it.name }.toSet()
        val rowRegex = Regex("""data-key="([^"]+)"""")
        val templateKeys = rowRegex.findAll(combined).map { it.groupValues[1] }.toSet()
        val unknown = templateKeys - keyNames
        assertTrue(
            unknown.isEmpty(),
            "These data-key values appear in the moderation templates but don't " +
                "match any ConfigDto.Configurations enum value (typo? renamed key? " +
                "stale row?): $unknown"
        )

        // Sanity check: catch the case where a future refactor empties
        // every template — the test would otherwise pass vacuously.
        assertTrue(
            templateKeys.size > 30,
            "Expected at least 30 distinct config rows across moderation templates; " +
                "found ${templateKeys.size}. Did the templates get gutted?"
        )
        // And that the allowlist isn't quietly absorbing every enum case.
        assertEquals(
            3, internalOnly.size,
            "internalOnly grew unexpectedly. Each entry must be justified — " +
                "explain in a comment above why the key never gets a UI surface."
        )
    }
}
