package bot.toby.command.commands.moderation

import bot.toby.modal.modals.setconfig.SetConfigActivityModal
import bot.toby.modal.modals.setconfig.SetConfigBlackjackRulesModal
import bot.toby.modal.modals.setconfig.SetConfigBlackjackTableModal
import bot.toby.modal.modals.setconfig.SetConfigFeesModal
import bot.toby.modal.modals.setconfig.SetConfigGeneralModal
import bot.toby.modal.modals.setconfig.SetConfigJackpotActivityModal
import bot.toby.modal.modals.setconfig.SetConfigJackpotModal
import bot.toby.modal.modals.setconfig.SetConfigLotteryBasicsModal
import bot.toby.modal.modals.setconfig.SetConfigLotteryPoolsModal
import bot.toby.modal.modals.setconfig.SetConfigPokerStakesModal
import bot.toby.modal.modals.setconfig.SetConfigPokerTableModal
import bot.toby.modal.modals.setconfig.SetConfigStakesModal
import core.command.CommandContext
import database.dto.ConfigDto.Configurations
import database.dto.UserDto
import database.service.guild.ConfigService
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/setconfig <subcommand>` — guild-owner-only configuration editor.
 *
 * Each subcommand opens a category-scoped modal that pre-populates
 * with the current values for its fields (admins see-and-edit; blank
 * fields skip the write). Replaced the old flat 25-option form, which
 * was at Discord's hard limit and forced ~45 other config keys onto
 * the web `/moderation` tab.
 *
 * Routing is by subcommand name → the matching modal bean's
 * `buildModal(modalId, currentValues)`. The `stakes` subcommand is
 * parameterised by a `game:<choice>` option whose value is encoded
 * into the modal `customId` as `setconfig_stakes:<game>`; the modal
 * manager routes by prefix and the modal handler parses the suffix.
 *
 * No `deferReply()` — the happy path is `replyModal`, which must be
 * the first interaction response. Permission failures (non-owner)
 * use `event.reply().setEphemeral(true)` accordingly.
 */
@Component
class SetConfigCommand @Autowired constructor(
    private val configService: ConfigService,
    private val general: SetConfigGeneralModal,
    private val activity: SetConfigActivityModal,
    private val fees: SetConfigFeesModal,
    private val jackpot: SetConfigJackpotModal,
    private val jackpotActivity: SetConfigJackpotActivityModal,
    private val pokerStakes: SetConfigPokerStakesModal,
    private val pokerTable: SetConfigPokerTableModal,
    private val blackjackRules: SetConfigBlackjackRulesModal,
    private val blackjackTable: SetConfigBlackjackTableModal,
    private val lotteryBasics: SetConfigLotteryBasicsModal,
    private val lotteryPools: SetConfigLotteryPoolsModal,
    private val stakes: SetConfigStakesModal,
) : ModerationCommand {

    override val name = "setconfig"
    override val description =
        "Guild-owner-only configuration. Use `/setconfig <category>` to open the matching form."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_GENERAL, "Audio + auto-delete + move/leaderboard channels"),
        SubcommandData(SUB_ACTIVITY, "Game-activity tracking + UBI + daily credit cap"),
        SubcommandData(SUB_FEES, "Loss tribute, jackpot win %, Toby Coin trade fees"),
        SubcommandData(SUB_JACKPOT, "Jackpot stake anchor, cooldown, RTP gate, modlog channel"),
        SubcommandData(SUB_JACKPOT_ACTIVITY, "Jackpot eligibility activity-day window"),
        SubcommandData(SUB_POKER_STAKES, "Poker blinds/bets/rake"),
        SubcommandData(SUB_POKER_TABLE, "Poker buy-ins, seat count, shot clock"),
        SubcommandData(SUB_BLACKJACK_RULES, "Blackjack rake, ante, dealer rule, shot clock"),
        SubcommandData(SUB_BLACKJACK_TABLE, "Blackjack seats + natural payout ratio"),
        SubcommandData(SUB_LOTTERY_BASICS, "Daily lottery on/off, ticket price, mode, ping"),
        SubcommandData(SUB_LOTTERY_POOLS, "Lottery seed/revenue split + announce channel"),
        SubcommandData(SUB_STAKES, "Per-game stake bounds (and bot-suspicion edge cap where applicable)")
            .addOptions(
                OptionData(OptionType.STRING, OPT_GAME, "Which game's stake bounds to edit", true)
                    .also { opt -> SetConfigStakesModal.Game.entries.forEach { opt.addChoice(it.label, it.token) } },
            ),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        if (ctx.member?.isOwner != true) {
            event.reply(
                "This is currently reserved for the owner of the server only, this may change in future"
            ).setEphemeral(true).queue()
            return
        }
        val guild = event.guild ?: run {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue()
            return
        }
        val guildId = guild.id
        val sub = event.subcommandName
        if (sub == null) {
            event.reply("Pick a subcommand — see `/setconfig` autocomplete.")
                .setEphemeral(true).queue()
            return
        }

        val reader: (Configurations) -> String? =
            { key -> configService.getConfigByName(key.configValue, guildId)?.value }

        val modal = when (sub) {
            SUB_GENERAL -> general.buildModal(SetConfigGeneralModal.MODAL_NAME, guild, reader)
            SUB_ACTIVITY -> activity.buildModal(SetConfigActivityModal.MODAL_NAME, guild, reader)
            SUB_FEES -> fees.buildModal(SetConfigFeesModal.MODAL_NAME, guild, reader)
            SUB_JACKPOT -> jackpot.buildModal(SetConfigJackpotModal.MODAL_NAME, guild, reader)
            SUB_JACKPOT_ACTIVITY -> jackpotActivity.buildModal(SetConfigJackpotActivityModal.MODAL_NAME, guild, reader)
            SUB_POKER_STAKES -> pokerStakes.buildModal(SetConfigPokerStakesModal.MODAL_NAME, guild, reader)
            SUB_POKER_TABLE -> pokerTable.buildModal(SetConfigPokerTableModal.MODAL_NAME, guild, reader)
            SUB_BLACKJACK_RULES -> blackjackRules.buildModal(SetConfigBlackjackRulesModal.MODAL_NAME, guild, reader)
            SUB_BLACKJACK_TABLE -> blackjackTable.buildModal(SetConfigBlackjackTableModal.MODAL_NAME, guild, reader)
            SUB_LOTTERY_BASICS -> lotteryBasics.buildModal(SetConfigLotteryBasicsModal.MODAL_NAME, guild, reader)
            SUB_LOTTERY_POOLS -> lotteryPools.buildModal(SetConfigLotteryPoolsModal.MODAL_NAME, guild, reader)
            SUB_STAKES -> {
                val token = event.getOption(OPT_GAME)?.asString
                val game = token?.let { SetConfigStakesModal.Game.byToken(it) } ?: run {
                    event.reply("Unknown game `$token` — pick one from the autocomplete.")
                        .setEphemeral(true).queue()
                    return
                }
                stakes.buildModal(SetConfigStakesModal.customIdFor(game), guild, reader)
            }
            else -> {
                event.reply("Unknown subcommand `$sub`.").setEphemeral(true).queue()
                return
            }
        }
        event.replyModal(modal).queue()
    }

    companion object {
        const val SUB_GENERAL = "general"
        const val SUB_ACTIVITY = "activity"
        const val SUB_FEES = "fees"
        const val SUB_JACKPOT = "jackpot"
        const val SUB_JACKPOT_ACTIVITY = "jackpot_activity"
        const val SUB_POKER_STAKES = "poker_stakes"
        const val SUB_POKER_TABLE = "poker_table"
        const val SUB_BLACKJACK_RULES = "blackjack_rules"
        const val SUB_BLACKJACK_TABLE = "blackjack_table"
        const val SUB_LOTTERY_BASICS = "lottery_basics"
        const val SUB_LOTTERY_POOLS = "lottery_pools"
        const val SUB_STAKES = "stakes"
        const val OPT_GAME = "game"
    }
}
