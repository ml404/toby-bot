package bot.toby.command.commands.misc

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class SupportCommand : MiscCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()

        val embed = EmbedBuilder()
            .setTitle("Support TobyBot")
            .setDescription(
                "TobyBot is free and open source. If you'd like to help cover hosting " +
                "costs or just say thanks, the links below are the best places to start."
            )
            .addField("Ko-fi", "[Support development](https://ko-fi.com/fratlayton)", false)
            .addField("GitHub", "[Source & issues](https://github.com/ml404/toby-bot)", false)
            .addField("Commands wiki", "[Browse all commands](https://github.com/ml404/toby-bot/wiki/Commands)", false)
            .build()

        event.hook.sendMessageEmbeds(embed)
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    override val name: String
        get() = "support"
    override val description: String
        get() = "Links for supporting TobyBot and finding help"
    override val optionData: List<OptionData>
        get() = emptyList()
}
