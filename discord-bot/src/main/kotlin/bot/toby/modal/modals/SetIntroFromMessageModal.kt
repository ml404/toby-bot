package bot.toby.modal.modals

import bot.toby.command.commands.music.intro.sendReplacePrompt
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.URLHelper
import common.intro.IntroClip
import common.intro.IntroSlots
import core.modal.Modal
import core.modal.ModalContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.modals.Modal as JdaModal
import org.springframework.stereotype.Component

/**
 * Volume and clip bounds for **Apps → Set as my intro**.
 *
 * The context menu used to save straight from the message, which made it the
 * one entry point with no way to trim — and, since the length rule lives with
 * the clip rules, the one that never checked how long the source was. A
 * ten-minute video became a ten-minute intro, reported as a success.
 *
 * The form is pre-filled with the guild's default volume and no clip, so
 * submitting it untouched does exactly what the old one-click flow did, minus
 * the missing validation. The source itself is carried in
 * [IntroHelper.pendingIntros] rather than the modal id — a Discord attachment
 * URL is far longer than the 100 characters a custom id allows.
 */
@Component
class SetIntroFromMessageModal(
    private val introHelper: IntroHelper,
) : Modal {

    override val name = MODAL_NAME

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val pending = introHelper.pendingIntros[event.user.idLong] ?: return reply(
            ctx,
            "That form lost track of which track it was for — right-click the message and try again.",
        )

        val rawVolume = event.getValue(FIELD_VOLUME)?.asString?.trim().orEmpty()
        val volume = if (rawVolume.isEmpty()) pending.volume else rawVolume.toIntOrNull()
        if (volume == null || volume !in 0..100) {
            return reply(ctx, "Volume must be a whole number between 0 and 100.")
        }

        val start = IntroClip.parseTimestamp(event.getValue(FIELD_START)?.asString, "Clip start")
        if (start is IntroClip.Parsed.Invalid) return reply(ctx, start.message)
        val end = IntroClip.parseTimestamp(event.getValue(FIELD_END)?.asString, "Clip end")
        if (end is IntroClip.Parsed.Invalid) return reply(ctx, end.message)

        val startMs = (start as IntroClip.Parsed.Ok).ms
        val endMs = (end as IntroClip.Parsed.Ok).ms

        // The same gate `/setintro` runs: one duration lookup, then the shared
        // clip rules decide. A long source is fine *if* it has been clipped,
        // which is exactly why the clip fields above are on this form.
        introHelper.validateClipAgainstSource(pending.url, startMs, endMs) { error ->
            if (error != null) {
                logger.info { "Intro from message rejected: $error" }
                reply(ctx, error)
                return@validateClipAgainstSource
            }
            save(ctx, deleteDelay, pending.copy(volume = volume, startMs = startMs, endMs = endMs))
        }
    }

    private fun save(ctx: ModalContext, deleteDelay: Int, pending: bot.toby.helpers.PendingIntro) {
        val event = ctx.event
        val requestingUserDto: UserDto = introHelper.findUserById(event.user.idLong, ctx.guild.idLong)
        introHelper.pendingIntros[event.user.idLong] = pending

        // At the slot limit the user still has to choose what to replace. The
        // pending intro now carries their volume and clip, so the menu picks up
        // where this left off.
        val intros = requestingUserDto.musicDtos
        if (intros.size >= IntroSlots.MAX_INTRO_COUNT) {
            sendReplacePrompt(event.hook, ctx.member, intros)
            return
        }

        val userName = ctx.member?.effectiveName ?: event.user.effectiveName
        pending.attachment?.let {
            introHelper.handleAttachment(
                event, requestingUserDto, userName, deleteDelay, it,
                pending.volume, null, pending.startMs, pending.endMs,
            )
            return
        }
        pending.url?.let {
            introHelper.handleUrl(
                event, requestingUserDto, userName, deleteDelay, URLHelper.fromUrlString(it),
                pending.volume, null, pending.startMs, pending.endMs,
            )
        }
    }

    private fun reply(ctx: ModalContext, message: String) {
        ctx.event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        const val MODAL_NAME = "setintrofrommessage"
        const val FIELD_VOLUME = "intro_volume"
        const val FIELD_START = "intro_start"
        const val FIELD_END = "intro_end"

        /** Room for `mm:ss.s` and then some. */
        private const val TIMESTAMP_FIELD_LIMIT = 12

        /** Discord modal titles are capped at 45 characters. */
        private const val TITLE_LIMIT = 45

        /**
         * @param sourceLabel the file name or link taken off the message, shown
         *        as the form's title so the user can see they right-clicked the
         *        message they meant to.
         */
        fun buildFor(sourceLabel: String, volume: Int): JdaModal {
            val volumeField = TextInput.create(FIELD_VOLUME, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, 3)
                .setValue(volume.toString())
                .build()
            val startField = TextInput.create(FIELD_START, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, TIMESTAMP_FIELD_LIMIT)
                .setPlaceholder("0:00")
                .build()
            val endField = TextInput.create(FIELD_END, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, TIMESTAMP_FIELD_LIMIT)
                .setPlaceholder("0:${"%02d".format(IntroClip.MAX_DURATION_SECONDS)}")
                .build()

            return JdaModal.create(MODAL_NAME, title(sourceLabel))
                .addComponents(
                    Label.of("Volume (0-100)", volumeField),
                    Label.of("Clip start — blank for none", startField),
                    Label.of("Clip end — blank for none", endField),
                )
                .build()
        }

        internal fun title(sourceLabel: String): String {
            val prefix = "Intro: "
            val room = TITLE_LIMIT - prefix.length
            val trimmed = sourceLabel.trim().ifEmpty { return "Set as my intro" }
            return prefix + if (trimmed.length <= room) trimmed else trimmed.take(room - 1) + "…"
        }
    }
}
