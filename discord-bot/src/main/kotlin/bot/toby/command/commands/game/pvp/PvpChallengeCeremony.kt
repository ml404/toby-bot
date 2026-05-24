package bot.toby.command.commands.game.pvp

import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.pvp.PvpSessionRegistry
import database.service.pvp.PvpWagerService
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import bot.toby.command.commands.game.pvp.PvpEmbeds

/**
 * Game-agnostic body for the `/<game> user: stake:` slash-command
 * handler. The three PvP commands (`/rps`, `/tictactoe`, `/connect4`)
 * carried verbatim copies of this ~60-line ceremony — guild / target /
 * bot / self validation, opponent lazy-create, service.startMatch
 * pre-flight, registry.register with a pending-timeout embed edit, and
 * the final pending-embed send. Only the per-game service / registry /
 * embeds objects vary.
 *
 * Per-game commands now reduce to: assemble the option metadata, hand
 * the [run] call references for the three rendering helpers + the two
 * domain actions, done. Adding a future PvP game gets the same body
 * for free.
 *
 * Pre-conditions assumed by callers:
 *  - The command has been registered with two options named "user"
 *    (USER, required) and "stake" (INTEGER, optional, min 0). The
 *    helper reads them by those names.
 *  - The registry type extends [PvpSessionRegistry] so its `register`
 *    method's `onPendingTimeout: (TSession) -> Unit` callback shape is
 *    consistent across games.
 */
object PvpChallengeCeremony {

    private const val OPT_USER = "user"
    private const val OPT_STAKE = "stake"

    fun <TSession : PvpSessionRegistry.Session> run(
        ctx: CommandContext,
        requestingUserDto: UserDto,
        deleteDelay: Int,
        userDtoHelper: UserDtoHelper,
        startMatch: (initiatorDiscordId: Long, opponentDiscordId: Long, guildId: Long, stake: Long) -> PvpWagerService.StartOutcome,
        register: (guildId: Long, initiatorDiscordId: Long, opponentDiscordId: Long, stake: Long, onPendingTimeout: (TSession) -> Unit) -> TSession,
        pendingEmbed: (initiatorDiscordId: Long, opponentDiscordId: Long, stake: Long) -> MessageEmbed,
        pendingButtons: (sessionId: Long, opponentDiscordId: Long) -> ActionRow,
        pendingTimeoutEmbed: (initiatorDiscordId: Long, opponentDiscordId: Long) -> MessageEmbed,
    ) {
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

        val start = startMatch(requestingUserDto.discordId, targetUser.idLong, guild.idLong, stake)
        if (start !is PvpWagerService.StartOutcome.Ok) {
            event.hook.replyEmbedAndDelete(
                PvpEmbeds.startErrorEmbed(PvpEmbeds.describeStartOutcome(start)),
                deleteDelay,
            )
            return
        }

        val initiatorId = requestingUserDto.discordId
        val opponentId = targetUser.idLong
        val session = register(guild.idLong, initiatorId, opponentId, stake) { expired ->
            // Pending-phase timeout — nothing was ever debited so just
            // edit the message in place. Best-effort: if the hook has
            // already expired or the message is gone there's nothing
            // useful to log.
            runCatching {
                event.hook.editOriginalEmbeds(
                    pendingTimeoutEmbed(expired.initiatorDiscordId, expired.opponentDiscordId)
                ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
            }
        }

        event.hook.sendMessageEmbeds(pendingEmbed(initiatorId, opponentId, stake))
            .addContent("<@$opponentId>")
            .addComponents(pendingButtons(session.id, opponentId))
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
