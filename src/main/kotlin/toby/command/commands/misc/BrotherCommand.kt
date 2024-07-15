package toby.command.commands.misc

import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.emote.Emotes
import toby.jpa.dto.UserDto
import toby.jpa.service.IBrotherService
import java.util.*

class BrotherCommand(private val brotherService: IBrotherService) : IMiscCommand {
    override val name = "brother"

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val tobyEmote: RichCustomEmoji? = event.guild?.jda?.getEmojiById(Emotes.TOBY)
        determineBrother(event, tobyEmote, deleteDelay ?: 0)
    }

    private fun determineBrother(event: SlashCommandInteractionEvent, tobyEmote: Emoji?, deleteDelay: Int) {
        val hook = event.hook
        val memberId = event.user.idLong
        val message = if (tobyId == memberId) {
            "You're not my fucking brother Toby, you're me $tobyEmote"
        } else {
            val mentions = Optional.ofNullable(event.getOption(name)).map { it.mentions }.map { it.members }.orElse(emptyList())
            if (mentions.isEmpty()) {
                lookupBrotherMessage(memberId, event.member!!.effectiveName, tobyEmote)
            } else {
                mentions.joinToString("\n") { lookupBrotherMessage(it.idLong, it.effectiveName, tobyEmote) }
            }
        }
        hook.sendMessage(message).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun lookupBrotherMessage(memberId: Long, memberName: String, tobyEmote: Emoji?): String {
        val brother = brotherService.getBrotherById(memberId)
        return brother?.let { "Of course you're my brother ${it.brotherName}." }
            ?: "$memberName is not my fucking brother ffs $tobyEmote"
    }

    override val description: String
        get() = "Let me tell you if you're my brother."

    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.USER, name, "Tag the person who you want to check the brother status of."))

    companion object {
        @JvmField
        var tobyId = 320919876883447808L
    }
}
