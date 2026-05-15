package bot.toby.button.buttons

import bot.toby.command.commands.misc.ExcuseCommand
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.service.ExcuseService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button as JdaButton
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class ExcuseListPageButton @Autowired constructor(
    private val excuseService: ExcuseService
) : Button {

    override val name: String = ExcuseCommand.BUTTON_NAME
    override val description: String = "Paginate the excuse list/search view."
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val componentId = event.componentId.lowercase()
        if (componentId == "${ExcuseCommand.BUTTON_NAME}:noop") {
            // The page indicator is disabled; this branch only exists to keep
            // the dispatcher quiet if Discord ever sends a click for it.
            event.deferEdit().queue()
            return
        }
        val parsed = ExcuseCommand.decodePageButton(componentId) ?: run {
            event.deferEdit().queue()
            return
        }

        val guildId = parsed.guildId

        if (parsed.scope == ExcuseCommand.SCOPE_PENDING && !requestingUserDto.superUser) {
            event.reply("You don't have permission to view pending excuses.").setEphemeral(true).queue()
            return
        }

        val paged = when (parsed.scope) {
            ExcuseCommand.SCOPE_PENDING -> excuseService.listPendingPaged(guildId, parsed.page, ExcuseCommand.EXCUSES_PER_PAGE)
            ExcuseCommand.SCOPE_SEARCH -> excuseService.searchApproved(
                guildId, parsed.query.orEmpty(), parsed.page, ExcuseCommand.EXCUSES_PER_PAGE
            )
            else -> excuseService.listApprovedPaged(guildId, parsed.page, ExcuseCommand.EXCUSES_PER_PAGE)
        }

        if (paged.rows.isEmpty()) {
            event.reply("That page is empty now — the list may have changed.").setEphemeral(true).queue()
            return
        }

        val embed = ExcuseCommand.buildPageEmbed(event.jda, guildId, paged, parsed.scope, parsed.query)
        val prevId = ExcuseCommand.encodePageButton(parsed.scope, guildId, paged.page - 1, parsed.query)
        val nextId = ExcuseCommand.encodePageButton(parsed.scope, guildId, paged.page + 1, parsed.query)

        event.editMessageEmbeds(embed).setComponents(
            ActionRow.of(
                JdaButton.primary(prevId, "⬅️ Prev").withDisabled(!paged.hasPrev),
                JdaButton.secondary("${ExcuseCommand.BUTTON_NAME}:noop", "Page ${paged.page}/${paged.totalPages}")
                    .withDisabled(true),
                JdaButton.primary(nextId, "Next ➡️").withDisabled(!paged.hasNext),
            )
        ).queue()
    }
}
