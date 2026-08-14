package bot.toby.profile

import bot.toby.button.buttons.economy.TipUserButton
import bot.toby.button.buttons.music.ViewIntrosButton
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * The profile-card message: the embed that frames the PNG, and the buttons
 * that lead off it.
 *
 * The card is a picture, so it is a dead end by itself — everything it shows
 * (level, balance, title) is the *result* of things you do elsewhere. The
 * buttons are what make right-clicking someone the start of an interaction
 * rather than the end of one: hear what they walk in to, or pay them for
 * whatever prompted the look.
 */
object ProfilePresenter {

    /** Referenced by the embed's `attachment://` image, so the two must agree. */
    const val ATTACHMENT_NAME = "profile.png"

    private val PROFILE_COLOR = Color(0x5B, 0x8D, 0xEF)

    fun embed(): MessageEmbed = EmbedBuilder()
        .setColor(PROFILE_COLOR)
        .setImage("attachment://$ATTACHMENT_NAME")
        .build()

    /**
     * @param isSelf drops the tip button, which the service would refuse
     *   anyway — offering a button that can only fail is worse than not
     *   offering it.
     */
    fun rows(target: Member, isSelf: Boolean): List<ActionRow> = listOf(
        ActionRow.of(
            buildList {
                add(ViewIntrosButton.button(target))
                // Bots hold no credit and the tip would bounce off TipService.
                if (!isSelf && !target.user.isBot) add(TipUserButton.button(target))
            }
        )
    )
}
