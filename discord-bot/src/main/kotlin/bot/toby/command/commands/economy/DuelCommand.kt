package bot.toby.command.commands.economy

import database.duel.PendingDuelRegistry
import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.service.DuelService
import database.service.DuelService.StartOutcome
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/duel user:<member> stake:<int>` — challenge another user to a 50/50
 * duel. Both players ante up the same stake when the opponent accepts;
 * winner takes the pot minus the per-guild jackpot tribute.
 *
 * The opponent's accept/decline click is handled by
 * [bot.toby.button.buttons.DuelButton], which atomically consumes the
 * pending offer from [PendingDuelRegistry] and resolves through
 * [DuelService.acceptDuel].
 */
@Component
class DuelCommand @Autowired constructor(
    private val duelService: DuelService,
    private val pendingDuelRegistry: PendingDuelRegistry,
    private val userDtoHelper: UserDtoHelper,
) : EconomyCommand {

    override val name: String = "duel"
    override val description: String =
        "Challenge another user to a 50/50 duel. Stake ${DuelService.MIN_STAKE}-${DuelService.MAX_STAKE} credits."

    companion object {
        private const val OPT_USER = "user"
        private const val OPT_STAKE = "stake"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, OPT_USER, "Opponent", true),
        OptionData(
            OptionType.INTEGER,
            OPT_STAKE,
            "Credits to wager each (${DuelService.MIN_STAKE}-${DuelService.MAX_STAKE})",
            true
        )
            .setMinValue(DuelService.MIN_STAKE)
            .setMaxValue(DuelService.MAX_STAKE)
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
            replyError(event, "You can't duel a bot.", deleteDelay); return
        }
        if (targetUser.idLong == requestingUserDto.discordId) {
            replyError(event, "You can't duel yourself.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            replyError(event, "You must specify a stake.", deleteDelay); return
        }

        // Lazy-create the opponent's user row so the pre-flight check can read their balance.
        userDtoHelper.calculateUserDto(targetUser.idLong, guild.idLong)

        val start = duelService.startDuel(
            initiatorDiscordId = requestingUserDto.discordId,
            opponentDiscordId = targetUser.idLong,
            guildId = guild.idLong,
            stake = stake
        )
        if (start !is StartOutcome.Ok) {
            event.hook.sendMessageEmbeds(DuelEmbeds.startErrorEmbed(start))
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        val initiatorId = requestingUserDto.discordId
        val opponentId = targetUser.idLong
        val offer = pendingDuelRegistry.register(
            guildId = guild.idLong,
            initiatorDiscordId = initiatorId,
            opponentDiscordId = opponentId,
            stake = stake
        ) { expired ->
            // Edit the offer message in place when the timer fires and the offer is still pending.
            // Use a best-effort lookup; if the message is gone there's nothing useful to do.
            runCatching {
                event.hook.editOriginalEmbeds(
                    DuelEmbeds.timeoutEmbed(expired.initiatorDiscordId, expired.opponentDiscordId, expired.stake)
                ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
            }
        }

        val accept = Button.success(
            DuelEmbeds.acceptButtonId(offer.id, opponentId),
            "Accept"
        )
        val decline = Button.danger(
            DuelEmbeds.declineButtonId(offer.id, opponentId),
            "Decline"
        )

        // sendMessage(content) (not sendMessageEmbeds) so the <@opponent> in the
        // content actually pings — embed-mention pings are silent.
        event.hook.sendMessage("<@$opponentId>")
            .setEmbeds(DuelEmbeds.offerEmbed(initiatorId, opponentId, stake, pendingDuelRegistry.ttl))
            .addComponents(ActionRow.of(accept, decline))
            .queue()
    }

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(DuelEmbeds.errorEmbed(message))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
