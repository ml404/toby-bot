package toby.command.commands.misc

import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
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
        val guild = event.guild!!
        val tobyEmote: Emoji? = guild.jda.getEmojiById(Emotes.TOBY)
        determineBrother(event, tobyEmote, deleteDelay!!)
    }

    private fun determineBrother(event: SlashCommandInteractionEvent, tobyEmote: Emoji?, deleteDelay: Int) {
        val hook = event.hook
        if (tobyId == event.user.idLong) {
            hook.sendMessageFormat("You're not my fucking brother Toby, you're me %s", tobyEmote)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val optionalMentions = Optional.ofNullable(event.getOption(name)).map { obj: OptionMapping -> obj.mentions }
        if (optionalMentions.isEmpty) {
            val brother = brotherService.getBrotherById(event.user.idLong)
            brother?.let {
                hook.sendMessageFormat("Of course you're my brother %s.", it.brotherName)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
                ?: run {
                    hook.sendMessageFormat("You're not my fucking brother %s ffs %s", event.user.name, tobyEmote)
                        .queue(invokeDeleteOnMessageResponse(deleteDelay))
                }
            return
        }
        val mentions = optionalMentions.get().members
        mentions.forEach { member ->
            val brother = brotherService.getBrotherById(member.idLong)
            brother?.let {
                hook.sendMessageFormat("Of course you're my brother %s.", it.brotherName)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            } ?: run {
                hook.sendMessageFormat("You're not my fucking brother %s ffs %s", event.user.name, tobyEmote)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
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