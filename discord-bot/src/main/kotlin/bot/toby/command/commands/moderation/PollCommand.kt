package bot.toby.command.commands.moderation

import bot.toby.modal.modals.PollModal
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.modals.Modal
import org.springframework.stereotype.Component

@Component
class PollCommand : ModerationCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        ctx.event.replyModal(buildModal()).queue()
    }

    private fun buildModal(): Modal {
        val builder = Modal.create(PollModal.MODAL_NAME, "Create poll")

        val question = TextInput.create(PollModal.FIELD_QUESTION, TextInputStyle.PARAGRAPH)
            .setPlaceholder("What are we polling?")
            .setRequiredRange(1, 500)
            .setRequired(true)
            .build()
        builder.addComponents(Label.of("Poll question", question))

        for (i in 1..PollModal.MAX_OPTIONS) {
            val option = TextInput.create("${PollModal.FIELD_OPTION_PREFIX}$i", TextInputStyle.SHORT)
                .setPlaceholder(if (i == 1) "First option" else "Optional")
                .setRequired(i == 1)
                .setRequiredRange(if (i == 1) 1 else 0, 100)
                .build()
            builder.addComponents(Label.of("Option $i", option))
        }

        return builder.build()
    }

    override val name: String
        get() = "poll"

    override val description: String
        get() = "Open a poll form. Fill in 1-10 options; reactions are added automatically."
}
