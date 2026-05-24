package bot.toby.button.buttons.lottery

import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.modals.Modal
import org.springframework.stereotype.Component

@Component
class LotteryBuyButton : Button {
    override val name = "lottery_buy"
    override val description = "Opens a modal to buy lottery tickets"
    override val defersReply = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val countInput = TextInput.create("count", TextInputStyle.SHORT)
            .setPlaceholder("1")
            .setRequiredRange(1, 4)
            .setRequired(true)
            .build()
        val modal = Modal.create("lottery_buy", "Buy Lottery Tickets")
            .addComponents(Label.of("Number of tickets", countInput))
            .build()
        ctx.event.replyModal(modal).queue()
    }
}
