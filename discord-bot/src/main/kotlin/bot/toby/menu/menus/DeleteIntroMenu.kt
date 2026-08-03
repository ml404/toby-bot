package bot.toby.menu.menus

import bot.toby.button.buttons.music.UndoDeleteIntroButton
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.DELETE_INTRO
import bot.toby.intro.IntroPresenter
import bot.toby.intro.IntroUndoStore
import core.menu.Menu
import core.menu.MenuContext
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class DeleteIntroMenu @Autowired constructor(
    private val introHelper: IntroHelper,
    private val undoStore: IntroUndoStore,
) : Menu {

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val selectedIntro = resolveSelectedIntroOrElse(ctx, introHelper, deleteDelay) ?: return

        // The selected id comes back from the client, so re-check it rather
        // than trusting that the menu only offered manageable intros.
        val actorId = ctx.event.user.idLong
        if (!introHelper.canManageIntro(selectedIntro.id, ctx.guild.idLong, actorId)) {
            logger.warn { "Rejecting delete of '${selectedIntro.id}' — not manageable by $actorId" }
            ctx.event.hook.sendMessage("That intro isn't yours to delete.").setEphemeral(true).queue()
            return
        }

        // Snapshot before deleting so the Undo button has something to put
        // back. Keyed on whoever pressed delete — they're the one looking at
        // the button — while the snapshot itself carries the owner's ids.
        undoStore.remember(selectedIntro, actorId)
        introHelper.deleteIntro(selectedIntro)

        val confirmation = MessageCreateBuilder()
            .setContent("Successfully deleted intro '${IntroPresenter.displayName(selectedIntro)}'")
            .setComponents(ActionRow.of(UndoDeleteIntroButton.button()))
            .build()
        ctx.event.hook.sendMessage(confirmation).setEphemeral(true).queue()
    }

    override val name: String get() = DELETE_INTRO
}
