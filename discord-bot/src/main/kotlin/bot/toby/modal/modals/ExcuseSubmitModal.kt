package bot.toby.modal.modals

import bot.toby.command.commands.misc.ExcuseCommand
import core.modal.Modal
import core.modal.ModalContext
import database.dto.social.ExcuseDto
import database.service.social.ExcuseService
import org.springframework.stereotype.Component

/**
 * Submission handler for the excuse-submission modal opened by
 * `/excuse submit`. Replaces the slash-option `text:<string>` field —
 * which was capped at one line of input and easy to abandon mid-typing
 * — with a paragraph form that invites longer, better-thought-out
 * submissions.
 */
@Component
class ExcuseSubmitModal(
    private val excuseService: ExcuseService,
) : Modal {
    override val name = MODAL_NAME

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guildId = ctx.guild.idLong
        val excuseText = event.getValue(FIELD_TEXT)?.asString?.trim().orEmpty()

        if (excuseText.isBlank()) {
            event.hook.sendMessage("Provide some excuse text.").setEphemeral(true).queue()
            return
        }

        val existing = excuseService.listAllGuildExcuses(guildId)
            .filterNotNull()
            .firstOrNull { it.excuse.equals(excuseText, ignoreCase = true) }
        if (existing != null) {
            event.hook.sendMessage(ExcuseCommand.EXISTING_EXCUSE_MESSAGE).setEphemeral(true).queue()
            return
        }

        val authorName = event.member?.effectiveName ?: event.user.name
        val authorDiscordId = event.user.idLong

        val dto = ExcuseDto(
            guildId = guildId,
            author = authorName,
            excuse = excuseText,
            authorDiscordId = authorDiscordId,
        )
        val saved = excuseService.createNewExcuse(dto)
        event.hook.sendMessage(
            "Submitted excuse '$excuseText' - $authorName with id '${saved?.id}' for approval."
        ).setEphemeral(true).queue()
    }

    companion object {
        const val MODAL_NAME = "excuse_submit"
        const val FIELD_TEXT = "excuse_text"
    }
}
