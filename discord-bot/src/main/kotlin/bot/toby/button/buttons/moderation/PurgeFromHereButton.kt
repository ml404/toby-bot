package bot.toby.button.buttons.moderation

import bot.toby.moderation.MessagePurge
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.channel.ChannelType
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.components.buttons.Button as JdaButton

/**
 * Carries out the deletion offered by **Purge from here**.
 *
 * Split from the entry that offers it because bulk deletion is irreversible and
 * a right-click is easy to misfire — the entry counts, this confirms.
 *
 * The channel is re-read here rather than trusting the count on the button.
 * Messages arrive while somebody reads a confirmation, and "everything after
 * that message" has to mean everything at the moment it happens, or the purge
 * leaves behind exactly the messages that prompted it.
 *
 * The result replaces the confirmation instead of arriving under it, which also
 * takes the button away — a second press would otherwise sweep up whatever had
 * been posted since the first.
 */
@Component
class PurgeFromHereButton : Button {

    override val name: String = BUTTON_NAME
    override val description: String = "Delete a message and everything posted after it."

    /** Replaces the confirmation it sits on, rather than replying under it. */
    override val defersEdit: Boolean = true

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val anchorId = event.componentId.split(':').getOrNull(1)?.toLongOrNull()
            ?: return finish(ctx, "That button has lost track of where to purge from — try again.")

        val member = ctx.member ?: return finish(ctx, "Couldn't work out who you are — try again.")
        if (event.channel.type != ChannelType.TEXT) {
            return finish(ctx, "Purge only works in text channels.")
        }
        val channel = event.channel.asTextChannel()

        // Re-checked rather than inherited from the entry: a role can change
        // between offering this and pressing it.
        if (!member.hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return finish(ctx, "You need Manage Messages in this channel.")
        }
        if (!ctx.guild.selfMember.hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return finish(ctx, "I need Manage Messages in this channel.")
        }

        channel.retrieveMessageById(anchorId).queue({ anchor ->
            channel.getHistoryAfter(anchorId, MessagePurge.MAX_BULK - 1).queue({ history ->
                val scope = MessagePurge.deletable(listOf(anchor) + history.retrievedHistory)
                // The anchor is always in the list, so an empty result means
                // everything in range has aged past Discord's 14-day line.
                if (scope.messages.isEmpty()) {
                    return@queue finish(
                        ctx,
                        "Those ${scope.tooOld} messages are all older than 14 days now, " +
                            "which Discord won't bulk-delete."
                    )
                }
                val reason = "Purged from a message by ${event.user.name}."
                MessagePurge.delete(channel, scope.messages, reason).queue({
                    logger.info { "Purged ${scope.messages.size} message(s) from $anchorId" }
                    val plural = if (scope.messages.size == 1) "" else "s"
                    finish(ctx, "Deleted ${scope.messages.size} message$plural${scope.suffix}.")
                }, { error ->
                    logger.error("Purge failed: ${error.message}")
                    finish(ctx, "Couldn't delete those: ${error.message}")
                })
            }, { error -> finish(ctx, "Couldn't read the channel history: ${error.message}") })
        }, {
            // Deleted between offer and press — someone else got there first,
            // and everything after it is a different question now.
            finish(ctx, "That message has gone — it may already have been deleted. Try again from another one.")
        })
    }

    /** Replaces the confirmation and takes its button away with it. */
    private fun finish(ctx: ButtonContext, message: String) {
        ctx.event.hook.editOriginal(message).setComponents().queue()
    }

    companion object {
        const val BUTTON_NAME = "purgefromhere"

        fun button(anchorMessageId: Long, count: Int): JdaButton = JdaButton.of(
            ButtonStyle.DANGER,
            "$BUTTON_NAME:$anchorMessageId",
            "Delete $count message${if (count == 1) "" else "s"}",
        )
    }
}
