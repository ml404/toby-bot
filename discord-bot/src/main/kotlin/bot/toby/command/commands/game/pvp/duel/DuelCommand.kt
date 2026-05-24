package bot.toby.command.commands.game.pvp.duel

import database.duel.PendingDuelRegistry
import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.pvp.duel.DuelService
import database.service.pvp.duel.DuelService.StartOutcome
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import bot.toby.command.commands.game.pvp.duel.DuelEmbeds
import bot.toby.command.commands.game.pvp.GameCommand

/**
 * `/duel user:<member> stake:<int>` — challenge another user to a 50/50
 * duel. Both players ante up the same stake when the opponent accepts;
 * winner takes the pot minus the per-guild jackpot tribute.
 *
 * The opponent's accept/decline click is handled by
 * [bot.toby.button.buttons.pvp.duel.DuelButton], which atomically consumes the
 * pending offer from [PendingDuelRegistry] and resolves through
 * [DuelService.acceptDuel].
 */
@Component
class DuelCommand @Autowired constructor(
    private val duelService: DuelService,
    private val pendingDuelRegistry: PendingDuelRegistry,
    private val userDtoHelper: UserDtoHelper,
) : GameCommand {

    override val name: String = "duel"
    override val description: String =
        "Challenge another user to a 50/50 duel. Stake bounds are per-guild (default ${DuelService.MIN_STAKE}-${DuelService.MAX_STAKE})."

    companion object {
        private const val OPT_USER = "user"
        private const val OPT_STAKE = "stake"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, OPT_USER, "Opponent", true),
        OptionData(
            OptionType.INTEGER,
            OPT_STAKE,
            "Credits to wager each (per-guild bounds; service rejects out-of-range)",
            true
        )
            .setMinValue(1L)
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
            event.hook.replyEmbedAndDelete(DuelEmbeds.startErrorEmbed(start), deleteDelay)
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

        // addContent on the message (not the embed description) so the
        // <@opponent> mention actually pings — embed-mention pings are silent.
        event.hook.sendMessageEmbeds(DuelEmbeds.offerEmbed(initiatorId, opponentId, stake, pendingDuelRegistry.ttl))
            .addContent("<@$opponentId>")
            .addComponents(ActionRow.of(accept, decline))
            .queue()
    }

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.replyEmbedAndDelete(DuelEmbeds.errorEmbed(message), deleteDelay)
    }
}
