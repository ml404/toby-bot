package bot.toby.command.commands.misc

import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.CommandContext
import database.dto.BrotherDto
import database.dto.UserDto
import database.service.social.BrotherService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

@Component
class BrotherCommand @Autowired constructor(
    private val brotherService: BrotherService,
) : MiscCommand {

    override val name = "brother"
    override val description = "Track who counts as a brother in this bot."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(CHECK, "Check whether you (or someone you mention) is a brother.")
            .addOptions(
                OptionData(OptionType.USER, OPT_USER, "User to check; defaults to yourself.", false),
            ),
        SubcommandData(LIST, "List everyone currently registered as a brother."),
        SubcommandData(ADD, "Register a user as a brother (superuser only).")
            .addOptions(
                OptionData(OptionType.USER, OPT_USER, "User to register.", true),
                OptionData(OptionType.STRING, OPT_NAME, "Display name to store for them.", true),
            ),
        SubcommandData(REMOVE, "Unregister a user (superuser only).")
            .addOptions(
                OptionData(OptionType.USER, OPT_USER, "User to unregister.", true),
            ),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        when (event.subcommandName) {
            CHECK, null -> handleCheck(event, deleteDelay)
            LIST -> handleList(event, deleteDelay)
            ADD -> handleAdd(event, requestingUserDto, deleteDelay)
            REMOVE -> handleRemove(event, requestingUserDto, deleteDelay)
            else -> event.hook.replyAndDelete(
                "Unknown subcommand. Try `/brother check`, `/brother list`, `/brother add`, or `/brother remove`.",
                deleteDelay,
            )
        }
    }

    private fun handleCheck(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val target = event.getOption(OPT_USER)?.asUser
        val (targetId, targetName) = if (target != null) {
            target.idLong to target.effectiveName
        } else {
            event.user.idLong to (event.member?.effectiveName ?: event.user.effectiveName)
        }
        event.hook.replyAndDelete(lookupBrotherMessage(targetId, targetName), deleteDelay)
    }

    private fun handleList(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val brothers = brotherService.listBrothers().filterNotNull()
        if (brothers.isEmpty()) {
            event.hook.replyAndDelete("No brothers are registered yet.", deleteDelay)
            return
        }
        val body = brothers.joinToString("\n") { "• ${it.brotherName} (`${it.discordId}`)" }
        val embed = EmbedBuilder()
            .setTitle("Brothers")
            .setColor(Color(0x66, 0x99, 0xCC))
            .setDescription(body)
            .build()
        event.hook.replyEmbedAndDelete(embed, deleteDelay)
    }

    private fun handleAdd(
        event: SlashCommandInteractionEvent,
        requesterDto: UserDto,
        deleteDelay: Int,
    ) {
        if (!requesterDto.superUser) {
            sendErrorMessage(event, deleteDelay)
            return
        }
        val target = event.getOption(OPT_USER)?.asUser ?: run {
            event.hook.replyAndDelete("You must specify a user to register.", deleteDelay)
            return
        }
        val displayName = event.getOption(OPT_NAME)?.asString?.trim()
        if (displayName.isNullOrBlank()) {
            event.hook.replyAndDelete("You must supply a display name.", deleteDelay)
            return
        }
        val existing = brotherService.getBrotherById(target.idLong)
        if (existing != null) {
            event.hook.replyAndDelete(
                "${target.effectiveName} is already registered as '${existing.brotherName}'.",
                deleteDelay,
            )
            return
        }
        brotherService.createNewBrother(BrotherDto(target.idLong, displayName))
        event.hook.replyAndDelete(
            "Registered ${target.effectiveName} as '$displayName'.",
            deleteDelay,
        )
    }

    private fun handleRemove(
        event: SlashCommandInteractionEvent,
        requesterDto: UserDto,
        deleteDelay: Int,
    ) {
        if (!requesterDto.superUser) {
            sendErrorMessage(event, deleteDelay)
            return
        }
        val target = event.getOption(OPT_USER)?.asUser ?: run {
            event.hook.replyAndDelete("You must specify a user to unregister.", deleteDelay)
            return
        }
        if (brotherService.getBrotherById(target.idLong) == null) {
            event.hook.replyAndDelete(
                "${target.effectiveName} isn't registered as a brother.",
                deleteDelay,
            )
            return
        }
        brotherService.deleteBrotherById(target.idLong)
        event.hook.replyAndDelete("Unregistered ${target.effectiveName}.", deleteDelay)
    }

    private fun lookupBrotherMessage(memberId: Long, memberName: String): String {
        val brother = brotherService.getBrotherById(memberId)
        return brother?.let { "Of course, ${it.brotherName} is one of my brothers." }
            ?: "$memberName is not registered as a brother."
    }

    companion object {
        const val CHECK = "check"
        const val LIST = "list"
        const val ADD = "add"
        const val REMOVE = "remove"

        private const val OPT_USER = "user"
        private const val OPT_NAME = "name"
    }
}
