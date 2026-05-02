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
}
