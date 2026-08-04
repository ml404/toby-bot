package bot.toby.menu.menus

import bot.toby.helpers.IntroHelper
import bot.toby.intro.IntroPresenter
import common.intro.IntroClip
import common.intro.IntroSlots
import core.menu.Menu
import core.menu.MenuContext
import database.dto.music.MusicDto
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Takes one of another member's intros for yourself, from the **View intros**
 * context menu.
 *
 * The slash-command equivalent is `/setintro copy`, which needs you to have
 * read `/listintros user:` first to know which slot number to ask for. Here
 * you are already looking at the list, so picking one off it is the whole
 * interaction.
 *
 * Options carry the source intro's id, which is `guildId_discordId_slot` and
 * therefore enough on its own to find the row again — no state to park, and
 * nothing to expire between opening the menu and answering it.
 */
@Component
class CopyIntroMenu @Autowired constructor(
    private val introHelper: IntroHelper,
) : Menu {

    override val name: String get() = MENU_NAME

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val guild = ctx.guild ?: return reply(ctx, "Intros are per-server — try this inside the server.")
        val source = event.values.firstOrNull()?.let { introHelper.findIntroById(it) }
            ?: return reply(ctx, "That intro has gone — it may have been deleted since you opened this.")

        val me = introHelper.findUserById(event.user.idLong, guild.idLong)
        val name = IntroPresenter.displayName(source)

        // No `into:` equivalent here: the menu is a one-click action, and
        // asking which of your own slots to sacrifice would need a second
        // menu. At the limit the answer is to go and free one up.
        val slot = introHelper.freeSlotFor(me) ?: return reply(
            ctx,
            "You already have ${IntroSlots.MAX_INTRO_COUNT} intros — free one up with `/deleteintro`, " +
                "or use `/setintro copy` with `into:` to say which to replace.",
        )

        val saved = introHelper.copyIntroInto(source, me, slot)
        if (saved == null) {
            logger.warn { "Failed to copy intro '${source.id}' into slot $slot for ${event.user.idLong}" }
            return reply(ctx, "Couldn't copy **$name** — you may already have it as one of your intros.")
        }

        logger.info { "Copied intro '${source.id}' into slot $slot for ${event.user.idLong}" }
        reply(
            ctx,
            "Copied **$name** into your slot #$slot " +
                "(volume ${source.introVolume ?: 100}%, clip ${IntroClip.describe(source.startMs, source.endMs)}).",
        )
    }

    private fun reply(ctx: MenuContext, message: String) {
        ctx.event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        const val MENU_NAME = "copyintro"

        fun menu(intros: Collection<MusicDto>): StringSelectMenu = StringSelectMenu.create(MENU_NAME)
            .setPlaceholder("Copy one to my intros")
            .addOptions(IntroPresenter.selectOptions(intros))
            .build()
    }
}
