package bot.toby.command.commands.game

import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.CommandContext
import database.dto.UserDto
import database.rps.RpsSessionRegistry
import database.service.PvpWagerService
import database.service.RpsService
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/rps user:<member> [stake:<int>]` — challenge another user to a
 * round of Rock-Paper-Scissors. Optional wager: when `stake > 0` both
 * players ante on accept and the winner takes the pot minus the
 * per-guild jackpot tribute (same model as `/duel`). When `stake` is
 * omitted or 0, the match is pure-fun and the winner only earns a
 * small XP grant via [database.service.XpAwardService].
 *
 * The opponent's accept/decline + both players' pick clicks are
 * handled by [bot.toby.button.buttons.RpsButton], which routes through
 * [RpsSessionRegistry] (in-memory session state) and [RpsService]
 * (wager arithmetic).
 */
@Component
class RpsCommand @Autowired constructor(
    private val rpsService: RpsService,
    private val rpsSessionRegistry: RpsSessionRegistry,
    private val userDtoHelper: UserDtoHelper,
) : GameCommand {

    override val name: String = "rps"
    override val description: String =
        "Challenge another user to Rock-Paper-Scissors. Stake is optional — leave it off for free play."

    companion object {
        private const val OPT_USER = "user"
        private const val OPT_STAKE = "stake"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, OPT_USER, "Opponent", true),
        OptionData(
            OptionType.INTEGER,
            OPT_STAKE,
            "Credits to wager each (optional; per-guild bounds; 0 = free play)",
            false,
        )
            .setMinValue(0L),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }
        val targetUser = event.getOption(OPT_USER)?.asUser ?: run {
            replyError(event, "You must specify an opponent.", deleteDelay); return
        }
        if (targetUser.isBot) {
            replyError(event, "You can't challenge a bot.", deleteDelay); return
        }
        if (targetUser.idLong == requestingUserDto.discordId) {
            replyError(event, "You can't challenge yourself.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: 0L

        // Lazy-create the opponent's user row so pre-flight balance check can read it.
        userDtoHelper.calculateUserDto(targetUser.idLong, guild.idLong)

        val start = rpsService.startMatch(
            initiatorDiscordId = requestingUserDto.discordId,
            opponentDiscordId = targetUser.idLong,
            guildId = guild.idLong,
            stake = stake,
        )
        if (start !is PvpWagerService.StartOutcome.Ok) {
            event.hook.replyEmbedAndDelete(
                PvpEmbeds.startErrorEmbed(PvpEmbeds.describeStartOutcome(start)),
                deleteDelay,
            )
            return
        }

        val initiatorId = requestingUserDto.discordId
        val opponentId = targetUser.idLong
        val session = rpsSessionRegistry.register(
            guildId = guild.idLong,
            initiatorDiscordId = initiatorId,
            opponentDiscordId = opponentId,
            stake = stake,
        ) { expired ->
            // Pending-phase timeout — nothing was ever debited so just
            // edit the message in place. Best-effort: if the hook has
            // already expired or the message is gone there's nothing
            // useful to log.
            runCatching {
                event.hook.editOriginalEmbeds(
                    RpsEmbeds.pendingTimeoutEmbed(expired.initiatorDiscordId, expired.opponentDiscordId)
                ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
            }
        }

        event.hook.sendMessageEmbeds(RpsEmbeds.pendingEmbed(initiatorId, opponentId, stake))
            .addContent("<@$opponentId>")
            .addComponents(RpsEmbeds.pendingButtons(session.id, opponentId))
            .queue()
    }

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int,
    ) {
        event.hook.replyEmbedAndDelete(PvpEmbeds.startErrorEmbed(message), deleteDelay)
    }
}
