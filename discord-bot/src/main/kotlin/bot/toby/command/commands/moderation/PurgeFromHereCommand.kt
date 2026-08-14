package bot.toby.command.commands.moderation

import bot.toby.button.buttons.moderation.PurgeFromHereButton
import bot.toby.moderation.MessagePurge
import core.command.MessageContextCommand
import database.dto.user.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component

/**
 * Right-click a message → **Apps → Purge from here**.
 *
 * `/purge count:` asks moderators for the wrong unit. Nobody knows the mess was
 * twenty-three messages long; they know *which* message it started at. Counting
 * back to a number is a step done only because the command demanded one, and
 * getting it wrong costs either an unfinished job or somebody's unrelated
 * message.
 *
 * This does not delete anything. Bulk deletion is irreversible and a right-click
 * is far easier to misfire than a typed command, so the entry only ever counts
 * what is in scope and hands over a button — see [PurgeFromHereButton], which is
 * where the deleting happens and which re-reads the channel at the moment it is
 * pressed rather than trusting the count below.
 *
 * The entry is hidden by default from members without Manage Messages, so a
 * moderation action doesn't sit in everybody's right-click menu. That is a
 * default a server can override, which is why the permission is checked here as
 * well as declared.
 */
@Component
class PurgeFromHereCommand : MessageContextCommand {

    override val name: String get() = COMMAND_NAME

    override val commandData: CommandData
        get() = Commands.message(name)
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))

    override fun handle(event: MessageContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(event.guild, event.member)

        val guild = event.guild ?: return reply(event, "Purging only works inside a server.")
        val member = event.member ?: return reply(event, "Couldn't work out who you are — try again.")
        val channel = event.channel?.takeIf { it.type == ChannelType.TEXT }?.asTextChannel()
            ?: return reply(event, "Purge only works in text channels.")

        if (!member.hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return reply(event, "You need Manage Messages in this channel.")
        }
        if (!guild.selfMember.hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return reply(event, "I need Manage Messages in this channel.")
        }

        val anchor = event.target
        // One short of the cap: the anchor is being deleted too, and the bulk
        // endpoint counts it.
        channel.getHistoryAfter(anchor.idLong, MessagePurge.MAX_BULK - 1).queue({ history ->
            val after = history.retrievedHistory
            val scope = MessagePurge.deletable(listOf(anchor) + after)

            // The anchor is always in the list, so the only way to end up with
            // nothing is for every message in range to be past the 14-day line.
            if (scope.messages.isEmpty()) {
                reply(
                    event,
                    "Nothing here can be deleted — all ${scope.tooOld} of those messages are older than " +
                        "14 days, which Discord won't bulk-delete."
                )
                return@queue
            }

            logger.info { "Offering to purge ${scope.messages.size} message(s) from ${anchor.idLong}" }

            val capped = after.size == MessagePurge.MAX_BULK - 1
            event.hook.sendMessage(confirmation(scope, anchor.author.effectiveName, capped))
                .addComponents(ActionRow.of(PurgeFromHereButton.button(anchor.idLong, scope.messages.size)))
                .setEphemeral(true)
                .queue()
        }, { error ->
            logger.error("Could not read channel history for a purge: ${error.message}")
            reply(event, "Couldn't read the channel history: ${error.message}")
        })
    }

    /**
     * Names the count and the anchor, because the two ways this goes wrong are
     * right-clicking the message next to the one you meant and not realising
     * how much sits below it.
     */
    private fun confirmation(scope: MessagePurge.Deletable, anchorAuthor: String, capped: Boolean): String =
        buildString {
            append("**${scope.messages.size} message${if (scope.messages.size == 1) "" else "s"}** ")
            append("will be deleted — $anchorAuthor's message and everything posted after it")
            append(scope.suffix)
            append(".")
            if (capped) {
                append(
                    "\n\nThat's as far as one purge reaches (${MessagePurge.MAX_BULK} messages); " +
                        "run it again afterwards if there's more."
                )
            }
            append("\n\nThis can't be undone. Dismiss this message to cancel.")
        }

    private fun reply(event: MessageContextInteractionEvent, message: String) {
        event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        /** Shown verbatim in Discord's Apps menu; max 32 characters. */
        const val COMMAND_NAME = "Purge from here"
    }
}
