package bot.toby.command.commands.music.intro

import bot.toby.button.buttons.music.PlayIntroButton
import bot.toby.helpers.IntroHelper
import bot.toby.intro.IntroPresenter
import bot.toby.menu.menus.CopyIntroMenu
import common.intro.IntroSlots
import core.command.UserContextCommand
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Right-click a member → **Apps → View intros**.
 *
 * The thing people actually do with intros is react to one: somebody joins,
 * the room hears it, and the next question is "what *was* that?". Answering it
 * meant knowing `/listintros` existed and that it took a `user:` option.
 *
 * Discord allows five user context entries per application, so this is one
 * entry rather than three: the list comes with a button to hear an intro and a
 * menu to take one, instead of "View intros", "Play intro" and "Copy intro"
 * each eating a slot in everybody's right-click menu.
 *
 * Open to anyone, for the same reason `/listintros user:` is — an intro plays
 * out loud to the whole channel on every join, so there is nothing here that
 * looking could reveal. The copy menu is left off your own list, where it
 * would only ever duplicate a row you already have.
 */
@Component
class ViewIntrosContextCommand @Autowired constructor(
    private val introHelper: IntroHelper,
) : UserContextCommand {

    override val name: String get() = COMMAND_NAME

    override fun handle(event: UserContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(event.guild, event.member)

        val guild = event.guild ?: return reply(
            event,
            "Intros are per-server — use this inside the server you want to look at.",
        )
        val target = event.targetMember ?: return reply(
            event,
            "${event.target.effectiveName} isn't a member of this server, so they have no intros here.",
        )

        val isSelf = target.idLong == event.user.idLong
        val targetDto = if (isSelf) requestingUserDto else introHelper.findUserById(target.idLong, guild.idLong)
        val intros = targetDto.musicDtos

        logger.info { "Showing ${intros.size} intro(s) for ${target.idLong}" }

        val rows = buildList {
            val buttons = buildList {
                // One button per intro rather than a single "play theirs": the
                // embed right above names them, and picking from a list only
                // to hear a different one is a strange thing to do to someone.
                // The slot cap keeps this to three, so they fit beside the
                // link in one row without ever needing a second menu.
                IntroPresenter.sorted(intros).forEach { add(PlayIntroButton.button(it)) }
                add(IntroPresenter.webDashboardButton(guild.idLong))
            }
            add(ActionRow.of(buttons))
            if (intros.isNotEmpty() && !isSelf) add(ActionRow.of(CopyIntroMenu.menu(intros)))
        }

        event.hook.sendMessageEmbeds(IntroPresenter.listEmbed(target, intros, IntroSlots.MAX_INTRO_COUNT))
            .addComponents(rows)
            .setEphemeral(true)
            .queue()
    }

    private fun reply(event: UserContextInteractionEvent, message: String) {
        event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        /** Shown verbatim in Discord's Apps menu; max 32 characters. */
        const val COMMAND_NAME = "View intros"
    }
}
