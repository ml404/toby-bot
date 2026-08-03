package bot.toby.button.buttons.music

import bot.toby.helpers.IntroHelper
import bot.toby.intro.IntroPresenter
import bot.toby.intro.IntroUndoStore
import common.intro.IntroSlots
import core.button.Button
import core.button.ButtonContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.buttons.Button as JdaButton
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Restores the intro the clicking user most recently removed via
 * `/deleteintro`, from the snapshot [IntroUndoStore] kept for them.
 *
 * Same safety net the web dashboard has had: a select-menu mis-click used to
 * be unrecoverable on Discord, with the only remedy being to find the original
 * file or URL again.
 */
@Component
class UndoDeleteIntroButton @Autowired constructor(
    private val introHelper: IntroHelper,
    private val undoStore: IntroUndoStore,
) : Button {

    override val name: String = BUTTON_NAME
    override val description: String = "Restore the intro you just deleted."

    // Edits the confirmation in place so the Undo button disappears once used —
    // no second ephemeral, and no stale button to double-click.
    override val defersReply: Boolean = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val guildId = ctx.guild.idLong
        val snapshot = undoStore.take(guildId, event.user.idLong)
        if (snapshot == null) {
            logger.info { "No intro snapshot to restore for user '${event.user.idLong}' on guild '$guildId'" }
            event.editMessage(
                "Nothing to undo — the ${IntroUndoStore.UNDO_WINDOW_MINUTES}-minute window may have passed, " +
                    "or the intro was already restored."
            ).setComponents().queue()
            return
        }

        val user = introHelper.findUserById(snapshot.discordId, snapshot.guildId)
        val existing = user.musicDtos
        if (existing.size >= IntroSlots.MAX_INTRO_COUNT) {
            event.editMessage("Can't restore — you're back at ${IntroSlots.MAX_INTRO_COUNT} intros already.")
                .setComponents().queue()
            return
        }

        // Prefer the original slot, but take the lowest free one if something
        // else claimed it while the undo was pending. Walking 1..MAX (rather
        // than max + 1) keeps the restored row inside the slot range even when
        // the remaining intros leave a gap — same rule as
        // IntroWebService.nextFreeIndex.
        val used = existing.mapNotNull { it.index }.toSet()
        val targetIndex = snapshot.index.takeIf { it !in used }
            ?: (1..IntroSlots.MAX_INTRO_COUNT).first { it !in used }

        val restored = MusicDto(
            user,
            targetIndex,
            snapshot.fileName,
            snapshot.introVolume,
            snapshot.musicBlob,
            snapshot.startMs,
            snapshot.endMs,
        )
        val saved = introHelper.createIntro(restored)
        if (saved == null) {
            logger.warn { "Failed to restore intro '${snapshot.fileName}' into slot $targetIndex" }
            event.editMessage("Couldn't restore that intro — it may already exist in another slot.")
                .setComponents().queue()
            return
        }

        logger.info { "Restored intro '${snapshot.fileName}' into slot $targetIndex" }
        event.editMessage("Restored **${IntroPresenter.displayName(restored)}** to slot #$targetIndex.")
            .setComponents().queue()
    }

    companion object {
        const val BUTTON_NAME = "undointro"

        fun button(): JdaButton = JdaButton.secondary(BUTTON_NAME, "Undo")
    }
}
