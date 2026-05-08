package web.template

import database.dto.ConfigDto
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presence regression for the per-game config rows on the moderation
 * page. The original miss was that every `BLACKJACK_*` key existed in
 * [ConfigDto.Configurations] and the `POST /moderation/{guildId}/config`
 * endpoint validated and persisted them, but the moderation HTML had no
 * `<input>` row to actually drive that POST — admins had to curl.
 *
 * This test reads `templates/moderation.html` off the classpath and
 * asserts each blackjack config key appears as a `data-key` attribute,
 * which is what the page's save-config JS keys off. Catches accidental
 * row removal during a future refactor.
 */
class ModerationTemplateRowsTest {

    private val html: String by lazy {
        val url = javaClass.classLoader.getResource("templates/moderation.html")
        assertNotNull(url, "moderation.html must be on the classpath")
        url!!.readText()
    }

    @Test
    fun `moderation template surfaces every blackjack config key as an editable row`() {
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
                html.contains("data-key=\"${key.name}\""),
                "moderation.html should have a config-row for ${key.name} so admins can edit it from the UI"
            )
        }
    }

    @Test
    fun `moderation template surfaces every poker config key as an editable row`() {
        // Same regression class as blackjack — `POKER_RAKE_PCT` was the only
        // poker key with a UI row before; the per-table parameters
        // (blinds, bets, buy-in range, max seats, shot clock) were defined
        // and validated server-side but admins had no form for them. The
        // chip-amount keys (blinds/bets/buy-ins) now live in the
        // consolidated "Casino stakes & buy-ins" card with the rest of
        // the stake bounds.
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
                html.contains("data-key=\"${key.name}\""),
                "moderation.html should have a config-row for ${key.name} so admins can edit it from the UI"
            )
        }
    }

    @Test
    fun `blackjack section in moderation template has its own collapsed details block`() {
        // The page wraps each game's keys in a `<details><summary>Game</summary>`
        // block so admins can scan to the right section. Without this wrapper
        // the keys would be lumped into "Economy & casino" alongside trade
        // fees and lose discoverability.
        assertTrue(
            html.contains("<summary>Blackjack</summary>"),
            "expected a <details><summary>Blackjack</summary> wrapper around the blackjack rows"
        )
    }

    @Test
    fun `poker section in moderation template has its own collapsed details block`() {
        assertTrue(
            html.contains("<summary>Poker</summary>"),
            "expected a <details><summary>Poker</summary> wrapper around the poker rows"
        )
    }

    @Test
    fun `moderation template surfaces every stake or buy-in config key as an editable row`() {
        // Every stake-shaped config — per-minigame min/max, blackjack ante
        // bounds, the jackpot scaling anchor, and the poker chip thresholds
        // (blinds/bets/buy-ins) — lives in the consolidated "Casino stakes
        // & buy-ins" card. This guards against a refactor accidentally
        // dropping a row (which would silently fall back to defaults
        // since no UI surface remains for the admin to edit it).
        val keys = listOf(
            ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR,
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
                html.contains("data-key=\"${key.name}\""),
                "moderation.html should have a config-row for ${key.name}"
            )
        }
    }

    @Test
    fun `casino stakes and buy-ins section has its own collapsed details block`() {
        assertTrue(
            html.contains("<summary>Casino stakes &amp; buy-ins</summary>"),
            "expected a <details><summary>Casino stakes & buy-ins</summary> wrapper around the consolidated stake rows"
        )
    }

    @Test
    fun `casino stakes card sub-groups every game with an h4 heading`() {
        // The card is too long without sub-headings — admins scan by game.
        // Asserting on a sample of headings so a flatten regression is
        // obvious without making the test enumerate every group.
        val headings = listOf(
            "<h4>Jackpot scaling</h4>",
            "<h4>Dice</h4>",
            "<h4>Blackjack</h4>",
            "<h4>Poker</h4>",
        )
        for (h in headings) {
            assertTrue(
                html.contains(h),
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
            val rowStart = html.indexOf(marker)
            assertTrue(rowStart >= 0, "row for ${key.name} not found")
            val rowEnd = html.indexOf("</div>", rowStart)
            assertTrue(rowEnd > rowStart, "row for ${key.name} not terminated")
            val rowHtml = html.substring(rowStart, rowEnd)
            assertTrue(
                rowHtml.contains("config-suffix") && rowHtml.contains(">%<"),
                "row for ${key.name} should include a `<span class=\"config-suffix\">%</span>` marker"
            )
        }
    }
}
