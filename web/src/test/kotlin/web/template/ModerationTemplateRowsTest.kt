package web.template

import database.dto.ConfigDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        // Stake-shaped keys (BLACKJACK_MIN_ANTE / BLACKJACK_MAX_ANTE) live
        // in the consolidated "Casino stakes & buy-ins" card now, but the
        // page still has to render rows for them — this test just asserts
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
        // lives in the consolidated "Casino stakes & buy-ins" card. The
        // jackpot stake anchor migrated up into the dedicated Jackpot
        // section in the split refactor; it's covered by the dedicated
        // jackpot test below.
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
    fun `casino stakes and buy-ins section has its own collapsed details block`() {
        assertTrue(
            settingsHtml.contains("<summary>Casino stakes &amp; buy-ins</summary>"),
            "expected a <details><summary>Casino stakes & buy-ins</summary> wrapper around the consolidated stake rows"
        )
    }

    @Test
    fun `casino stakes card sub-groups every game with an h4 heading`() {
        // The card is too long without sub-headings — admins scan by game.
        // Asserting on a sample of headings so a flatten regression is
        // obvious without making the test enumerate every group.
        val headings = listOf(
            "<h4>Dice</h4>",
            "<h4>Blackjack</h4>",
            "<h4>Poker</h4>",
        )
        for (h in headings) {
            assertTrue(
                settingsHtml.contains(h),
                "expected $h sub-heading inside the consolidated card"
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
            ConfigDto.Configurations.JACKPOT_PAYOUT_PCT,
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
            ConfigDto.Configurations.JACKPOT_PAYOUT_PCT,
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
        val internalOnly = setOf(
            ConfigDto.Configurations.ACTIVITY_TRACKING_NOTIFIED,
        )

        val templates = listOf(
            readTemplate("templates/moderation/users.html"),
            readTemplate("templates/moderation/settings.html"),
            readTemplate("templates/moderation/voice.html"),
            readTemplate("templates/moderation/poll.html"),
            readTemplate("templates/moderation/casino.html"),
            readTemplate("templates/moderation/lottery.html"),
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
            1, internalOnly.size,
            "internalOnly grew unexpectedly. Each entry must be justified — " +
                "explain in a comment above why the key never gets a UI surface."
        )
    }
}
