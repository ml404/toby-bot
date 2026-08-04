package bot.toby.command.commands.music.intro

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.PendingIntro
import bot.toby.modal.modals.SetIntroFromMessageModal
import core.command.MessageContextCommand
import database.dto.user.UserDto
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Right-click a message → **Apps → Set as my intro**.
 *
 * Someone drops a track in chat and the reaction is "that should be my intro"
 * — which previously meant re-uploading the file or copying the link back into
 * `/setintro`. This takes it straight off the message: the first MP3
 * attachment if there is one, otherwise the first link in the message body.
 *
 * Always sets the *invoking* user's intro, never the message author's — the
 * super-user targeting on `/setintro` has no equivalent here, and silently
 * changing someone else's intro from their own message would be a trap.
 *
 * Picking the source is all this does. It opens [SetIntroFromMessageModal] for
 * the volume and clip bounds, which is also where the intro is validated and
 * saved. Going straight to the save skipped the clip form entirely, so this
 * entry point was the one that couldn't trim — and, because the duration check
 * lives with the clip rules, the one that would happily set a ten-minute video
 * as somebody's intro.
 */
@Component
class SetIntroFromMessageCommand @Autowired constructor(
    private val introHelper: IntroHelper,
) : MessageContextCommand {

    override val name: String get() = COMMAND_NAME

    /** Opens a modal, which has to be the interaction's first response. */
    override val defersReply: Boolean = false

    override fun handle(event: MessageContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(event.guild, event.member)

        val source = extractSource(event.target) ?: run {
            // Not deferred, so this replies directly rather than via the hook.
            event.reply(
                "That message has no MP3 attachment or link to use. Attach an MP3 (or post a YouTube link) " +
                    "and try again, or use `/setintro`."
            ).setEphemeral(true).queue()
            return
        }

        val volume = introHelper.defaultIntroVolume(event.guild?.id)

        // Stashed for the modal to pick up: an attachment URL is far too long
        // for a modal custom id, and the id travels through the client anyway.
        introHelper.pendingIntros[requestingUserDto.discordId] = PendingIntro(
            attachment = (source as? Source.File)?.attachment,
            url = (source as? Source.Link)?.url,
            volume = volume,
        )

        event.replyModal(
            SetIntroFromMessageModal.buildFor(
                sourceLabel = when (source) {
                    is Source.File -> source.attachment.fileName
                    is Source.Link -> source.url
                },
                volume = volume,
            )
        ).queue()
    }

    /**
     * An MP3 attachment wins over a link: if someone posted both, the file is
     * the thing they shared and the link is probably context.
     */
    private fun extractSource(message: Message): Source? {
        message.attachments.firstOrNull { it.fileExtension.equals("mp3", ignoreCase = true) }
            ?.let { return Source.File(it) }
        return URL_PATTERN.find(message.contentRaw)?.value?.let { Source.Link(it) }
    }

    private sealed interface Source {
        data class File(val attachment: Attachment) : Source
        data class Link(val url: String) : Source
    }

    companion object {
        /** Shown verbatim in Discord's Apps menu; max 32 characters. */
        const val COMMAND_NAME = "Set as my intro"

        private val URL_PATTERN = Regex("""https?://\S+""")
    }
}
