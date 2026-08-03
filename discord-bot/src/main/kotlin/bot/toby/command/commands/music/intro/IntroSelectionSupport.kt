package bot.toby.command.commands.music.intro

import bot.toby.intro.IntroPresenter
import common.intro.IntroSlots
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/**
 * Shared "pick one of your intros" reply behind `/editintro` and
 * `/deleteintro`. Both used to build the menu themselves, in raw list order
 * and with unbounded option labels — see [IntroPresenter] for what that broke.
 *
 * The reply is ephemeral: whatever follows (an edit modal, a delete
 * confirmation) is private too, and the menu shouldn't linger in the channel.
 */
internal fun sendIntroSelection(
    event: SlashCommandInteractionEvent,
    requestingUserDto: UserDto,
    menuId: String,
    placeholder: String,
    emptyMessage: String,
) {
    val intros = requestingUserDto.musicDtos
    if (intros.isEmpty()) {
        event.hook.sendMessage(emptyMessage).setEphemeral(true).queue()
        return
    }

    val menu = StringSelectMenu.create(menuId)
        .setPlaceholder(placeholder)
        .addOptions(IntroPresenter.selectOptions(intros))
        .build()

    val member = event.member
    val action = if (member != null) {
        event.hook.sendMessageEmbeds(IntroPresenter.listEmbed(member, intros, IntroSlots.MAX_INTRO_COUNT))
    } else {
        event.hook.sendMessage(placeholder)
    }

    action.addComponents(ActionRow.of(menu)).setEphemeral(true).queue()
}
