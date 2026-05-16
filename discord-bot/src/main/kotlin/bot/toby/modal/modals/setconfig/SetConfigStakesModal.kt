package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig stakes game:<choice>` — per-game stake bounds. The
 * slash command's `game` choice option is encoded into the modal
 * `customId` as `setconfig_stakes:<game>` (lowercase) so the modal
 * handler knows which `*_MIN_STAKE`/`*_MAX_STAKE` (and, where
 * present, `*_BOT_EDGE_MAX_PCT`) keys to write.
 *
 * `DefaultModalManager.getModal` routes on the substring before `:`
 * — so [MODAL_NAME] = `"setconfig_stakes"` matches every game.
 *
 * Adding a new game means adding a new [Game] entry — nothing else
 * to touch.
 */
@Component
class SetConfigStakesModal(
    configService: ConfigService,
) : SetConfigCategoryModal(configService) {

    override val name = MODAL_NAME

    override fun titleFor(modalId: String): String {
        val game = parseGame(modalId)
        return "${game.label} stakes"
    }

    override fun specsFor(modalId: String): LinkedHashMap<Configurations, FieldSpec> {
        val game = parseGame(modalId)
        return linkedMapOf<Configurations, FieldSpec>().apply {
            put(game.minKey, FieldSpec.LongMin("Min stake (credits)", 1L))
            put(game.maxKey, FieldSpec.LongMin("Max stake (credits)", 1L))
            game.botEdgeKey?.let {
                put(it, FieldSpec.IntRange("Bot-suspicion max edge %", 0..50))
            }
        }
    }

    override fun fieldIdsFor(modalId: String): Map<Configurations, String> {
        val game = parseGame(modalId)
        return buildMap {
            put(game.minKey, FIELD_MIN_STAKE)
            put(game.maxKey, FIELD_MAX_STAKE)
            game.botEdgeKey?.let { put(it, FIELD_BOT_EDGE_PCT) }
        }
    }

    private fun parseGame(modalId: String): Game {
        val token = modalId.substringAfter(':', missingDelimiterValue = "")
        return Game.byToken(token)
            ?: error("Stakes modal customId missing or unknown game token: '$modalId'")
    }

    /**
     * The 10 casino games exposed through `/setconfig stakes`. Each
     * entry binds a lowercase token (used in slash-option choices and
     * in the modal customId suffix), a human-friendly label for the
     * modal title, and the per-game ConfigDto keys.
     */
    enum class Game(
        val token: String,
        val label: String,
        val minKey: Configurations,
        val maxKey: Configurations,
        val botEdgeKey: Configurations? = null,
    ) {
        DICE("dice", "Dice", Configurations.DICE_MIN_STAKE, Configurations.DICE_MAX_STAKE, Configurations.DICE_BOT_EDGE_MAX_PCT),
        COINFLIP("coinflip", "Coinflip", Configurations.COINFLIP_MIN_STAKE, Configurations.COINFLIP_MAX_STAKE, Configurations.COINFLIP_BOT_EDGE_MAX_PCT),
        SLOTS("slots", "Slots", Configurations.SLOTS_MIN_STAKE, Configurations.SLOTS_MAX_STAKE, Configurations.SLOTS_BOT_EDGE_MAX_PCT),
        HIGHLOW("highlow", "Highlow", Configurations.HIGHLOW_MIN_STAKE, Configurations.HIGHLOW_MAX_STAKE),
        BACCARAT("baccarat", "Baccarat", Configurations.BACCARAT_MIN_STAKE, Configurations.BACCARAT_MAX_STAKE),
        KENO("keno", "Keno", Configurations.KENO_MIN_STAKE, Configurations.KENO_MAX_STAKE),
        SCRATCH("scratch", "Scratch", Configurations.SCRATCH_MIN_STAKE, Configurations.SCRATCH_MAX_STAKE),
        ROULETTE("roulette", "Roulette", Configurations.ROULETTE_MIN_STAKE, Configurations.ROULETTE_MAX_STAKE),
        HOLDEM("holdem", "Casino Hold'em", Configurations.HOLDEM_MIN_STAKE, Configurations.HOLDEM_MAX_STAKE),
        DUEL("duel", "Duel", Configurations.DUEL_MIN_STAKE, Configurations.DUEL_MAX_STAKE);

        companion object {
            private val BY_TOKEN: Map<String, Game> = entries.associateBy { it.token }
            fun byToken(token: String): Game? = BY_TOKEN[token.lowercase()]
        }
    }

    companion object {
        const val MODAL_NAME = "setconfig_stakes"
        const val FIELD_MIN_STAKE = "min_stake"
        const val FIELD_MAX_STAKE = "max_stake"
        const val FIELD_BOT_EDGE_PCT = "bot_edge_pct"

        /** Build the `customId` the slash command opens for a given game. */
        fun customIdFor(game: Game): String = "$MODAL_NAME:${game.token}"
    }
}
