package bot.toby.command.commands.music.intro

import bot.toby.helpers.MenuHelper.SET_INTRO
import bot.toby.intro.IntroPresenter
import common.intro.IntroSlots
import database.dto.music.MusicDto
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook

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

    sendIntroMenu(event.hook, event.member, intros, menuId, placeholder, null)
}

/**
 * The "you're at the limit, pick one to replace" prompt. Shared by every
 * entry point that can add an intro — `/setintro` and the message context
 * menu — so they can't drift on the wording or the menu it hands back.
 */
internal fun sendReplacePrompt(hook: InteractionHook, member: Member?, intros: Collection<MusicDto>) {
    sendIntroMenu(
        hook = hook,
        member = member,
        intros = intros,
        menuId = SET_INTRO,
        placeholder = "Select the intro to replace",
        content = "Select the intro you'd like to replace with your new upload — " +
            "we only allow ${IntroSlots.MAX_INTRO_COUNT} intros.",
    )
}

private fun sendIntroMenu(
    hook: InteractionHook,
    member: Member?,
    intros: Collection<MusicDto>,
    menuId: String,
    placeholder: String,
    content: String?,
) {
    val menu = StringSelectMenu.create(menuId)
        .setPlaceholder(placeholder)
        .addOptions(IntroPresenter.selectOptions(intros))
        .build()

    // The embed needs a member for its title and avatar; without one (a DM,
    // in principle) fall back to plain text rather than dropping the menu.
    val action = if (member != null) {
        hook.sendMessageEmbeds(IntroPresenter.listEmbed(member, intros, IntroSlots.MAX_INTRO_COUNT))
    } else {
        hook.sendMessage(content ?: placeholder)
    }

    (if (content != null && member != null) action.addContent(content) else action)
        .addComponents(ActionRow.of(menu))
        .setEphemeral(true)
        .queue()
}
