package bot.toby.command.commands.economy

import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.CommandContext
import database.connect4.Connect4SessionRegistry
import database.dto.UserDto
import database.service.Connect4Service
import database.service.PvpWagerService
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/connect4 user:<member> [stake:<int>]` — challenge another user
 * to a round of Connect 4. Optional wager: when `stake > 0` both
 * players ante on accept and the winner takes the pot minus the
 * per-guild jackpot tribute (same model as `/rps` and `/tictactoe`).
 * When `stake` is omitted or 0, the match is pure-fun and the winner
 * only earns a small XP grant.
 *
 * Initiator plays 🔴 (drops first); opponent plays 🟡.
 *
 * Accept/decline + per-column drop + forfeit clicks are handled by
 * [bot.toby.button.buttons.Connect4Button], which routes through
 * [Connect4SessionRegistry] (in-memory board + turn state) and
 * [Connect4Service] (wager arithmetic).
 */
@Component
class Connect4Command @Autowired constructor(
    private val connect4Service: Connect4Service,
    private val connect4SessionRegistry: Connect4SessionRegistry,
    private val userDtoHelper: UserDtoHelper,
) : EconomyCommand {

    override val name: String = "connect4"
    override val description: String =
        "Challenge another user to Connect 4. Stake is optional — leave it off for free play."

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
        ).setMinValue(0L),
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

        userDtoHelper.calculateUserDto(targetUser.idLong, guild.idLong)

        val start = connect4Service.startMatch(
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
        val session = connect4SessionRegistry.register(
            guildId = guild.idLong,
            initiatorDiscordId = initiatorId,
            opponentDiscordId = opponentId,
            stake = stake,
        ) { expired ->
            runCatching {
                event.hook.editOriginalEmbeds(
                    Connect4Embeds.pendingTimeoutEmbed(expired.initiatorDiscordId, expired.opponentDiscordId)
                ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
            }
        }

        event.hook.sendMessageEmbeds(Connect4Embeds.pendingEmbed(initiatorId, opponentId, stake))
            .addContent("<@$opponentId>")
            .addComponents(Connect4Embeds.pendingButtons(session.id, opponentId))
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
