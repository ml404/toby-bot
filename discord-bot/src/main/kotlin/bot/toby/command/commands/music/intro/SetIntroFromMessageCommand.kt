package bot.toby.command.commands.music.intro

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.PendingIntro
import bot.toby.helpers.URLHelper
import common.intro.IntroSlots
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
 * Clip bounds aren't part of this flow; `/editintro` trims afterwards.
 */
@Component
class SetIntroFromMessageCommand @Autowired constructor(
    private val introHelper: IntroHelper,
) : MessageContextCommand {

    override val name: String get() = COMMAND_NAME

    override fun handle(event: MessageContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(event.guild, event.member)

        val source = extractSource(event.target) ?: run {
            event.hook.sendMessage(
                "That message has no MP3 attachment or link to use. Attach an MP3 (or post a YouTube link) " +
                    "and try again, or use `/setintro`."
            ).setEphemeral(true).queue()
            return
        }

        val volume = introHelper.defaultIntroVolume(event.guild?.id)

        // At the slot limit the user has to choose what to replace, exactly as
        // `/setintro` does — stash the pick and hand them the same menu.
        val intros = requestingUserDto.musicDtos
        if (intros.size >= IntroSlots.MAX_INTRO_COUNT) {
            introHelper.pendingIntros[requestingUserDto.discordId] = PendingIntro(
                attachment = (source as? Source.File)?.attachment,
                url = (source as? Source.Link)?.url,
                volume = volume,
            )
            sendReplacePrompt(event.hook, event.member, intros)
            return
        }

        val userName = event.member?.effectiveName ?: event.user.effectiveName
        when (source) {
            is Source.File -> introHelper.handleAttachment(
                event, requestingUserDto, userName, deleteDelay, source.attachment, volume
            )
            is Source.Link -> introHelper.handleUrl(
                event, requestingUserDto, userName, deleteDelay, URLHelper.fromUrlString(source.url), volume
            )
        }
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
