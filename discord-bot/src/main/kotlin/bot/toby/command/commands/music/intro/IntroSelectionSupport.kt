package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.IntroHelper
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
    target: IntroTarget,
    menuId: String,
    placeholder: String,
    emptyMessage: String,
) {
    val intros = target.userDto.musicDtos
    if (intros.isEmpty()) {
        event.hook.sendMessage(emptyMessage).setEphemeral(true).queue()
        return
    }

    sendIntroMenu(event.hook, target.member, intros, menuId, placeholder, null)
}

/** Whose intros a command is acting on. */
internal data class IntroTarget(val member: Member, val userDto: UserDto)

/** Option name shared by the intro commands that can act on someone else. */
internal const val INTRO_USER_OPTION = "user"

/**
 * Resolves whose intros to act on. Defaults to the caller; a `user:` option
 * targets someone else.
 *
 * @param readOnly true for commands that only *look* at intros. Changing
 *        someone else's is still super-user only, but reading one never needed
 *        to be: an intro announces itself out loud to everyone in the channel
 *        on every join, so treating the list of them as privileged information
 *        only ever stopped people finding out what that thing they liked was.
 * @return the target, or null when the caller was rejected (the refusal has
 *         already been sent) or the command ran outside a guild.
 */
internal fun MusicCommand.resolveIntroTarget(
    event: SlashCommandInteractionEvent,
    requestingUserDto: UserDto,
    introHelper: IntroHelper,
    deleteDelay: Int,
    readOnly: Boolean = false,
): IntroTarget? {
    val requested = event.getOption(INTRO_USER_OPTION)?.asMember
    if (requested != null && requested.idLong != event.user.idLong && !readOnly && !requestingUserDto.superUser) {
        sendErrorMessage(event, deleteDelay)
        return null
    }

    val member = requested ?: event.member ?: run {
        event.hook.sendMessage("Intros are per-server — run this inside the server you want to manage.")
            .setEphemeral(true).queue()
        return null
    }

    val userDto = if (member.idLong == event.user.idLong) {
        requestingUserDto
    } else {
        introHelper.findUserById(member.idLong, member.guild.idLong)
    }
    return IntroTarget(member, userDto)
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
