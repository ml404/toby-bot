package bot.toby.intro

import bot.toby.button.buttons.misc.ViewProfileButton
import bot.toby.button.buttons.music.PlayIntroButton
import bot.toby.menu.menus.CopyIntroMenu
import common.intro.IntroSlots
import database.dto.music.MusicDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * The intro-list message, as shown by the **View intros** right-click entry and
 * by the button that crosses over from a profile card.
 *
 * Both want exactly the same thing, and the part worth keeping in one place is
 * the row layout: Discord caps an action row at five components, and the count
 * here moves with how many intros somebody has.
 */
object IntroListView {

    data class Rendered(val embed: MessageEmbed, val rows: List<ActionRow>)

    /**
     * @param isSelf drops the copy menu, which on your own list could only ever
     *   duplicate a row you already have.
     */
    fun of(target: Member, intros: Collection<MusicDto>, isSelf: Boolean): Rendered {
        val ordered = IntroPresenter.sorted(intros)
        val rows = buildList {
            // Playing and going-elsewhere are separate rows rather than one
            // packed row. Three slots plus two links is exactly five, which
            // fits today and breaks silently the day a fourth slot is added.
            if (ordered.isNotEmpty()) add(ActionRow.of(ordered.map { PlayIntroButton.button(it) }))
            add(ActionRow.of(ViewProfileButton.button(target), IntroPresenter.webDashboardButton(target.guild.idLong)))
            if (ordered.isNotEmpty() && !isSelf) add(ActionRow.of(CopyIntroMenu.menu(intros)))
        }
        return Rendered(
            embed = IntroPresenter.listEmbed(target, intros, IntroSlots.MAX_INTRO_COUNT),
            rows = rows,
        )
    }
}
