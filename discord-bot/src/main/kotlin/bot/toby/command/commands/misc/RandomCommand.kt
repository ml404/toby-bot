package bot.toby.command.commands.misc

import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class RandomCommand : MiscCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val rawList = event.getOption(LIST)?.asString
        val options = parseOptions(rawList)
        pick(event.hook, options, event.user.effectiveName, deleteDelay)
    }

    /**
     * Shared entry point so [bot.toby.button.buttons.RandomButton] can
     * re-roll against the same options without rebuilding the parsing
     * logic. Caller must have acknowledged the interaction already
     * (`deferReply()` for the slash command, `deferEdit()` for the
     * button).
     */
    fun pick(hook: InteractionHook, options: List<String>, askedBy: String, deleteDelay: Int) {
        if (options.isEmpty()) {
            hook.editOriginalEmbeds(listOf(RandomEmbeds.noOptionsEmbed(description))).queue {
                scheduleDelete(hook, deleteDelay)
            }
            return
        }
        val winner = options.random()
        val edit = hook.editOriginalEmbeds(listOf(RandomEmbeds.wheelEmbed(winner, options, askedBy)))
        val row = RandomEmbeds.pickAgainRow(options)
        if (row != null) edit.setComponents(row)
        edit.queue { scheduleDelete(hook, deleteDelay) }
    }

    private fun scheduleDelete(hook: InteractionHook, deleteDelay: Int) {
        if (deleteDelay > 0) {
            hook.deleteOriginal().queueAfter(
                deleteDelay.toLong(),
                java.util.concurrent.TimeUnit.SECONDS,
            )
        }
    }

    override val name: String get() = "random"
    override val description: String
        get() = "Return one item from a list you provide with options separated by commas."
    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(
                OptionType.STRING,
                LIST,
                "List of elements you want to pick a random value from",
                true,
            )
        )

    companion object {
        const val LIST = "list"

        fun parseOptions(rawList: String?): List<String> =
            rawList?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
    }
}
