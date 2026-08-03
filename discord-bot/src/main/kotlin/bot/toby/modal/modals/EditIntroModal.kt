package bot.toby.modal.modals

import bot.toby.helpers.IntroHelper
import bot.toby.intro.IntroPresenter
import common.intro.IntroClip
import core.modal.Modal
import core.modal.ModalContext
import database.dto.music.MusicDto
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.modals.Modal as JdaModal
import org.springframework.stereotype.Component

/**
 * Submission handler for the intro editor opened by `/editintro`.
 *
 * The previous flow asked the user to type a bare number into the channel and
 * raced a 10-second [bot.toby.handler.EventWaiter] against them: miss the
 * window and the edit was silently cancelled, hit it and the bot deleted your
 * message afterwards. It also only ever exposed volume, so renaming an intro
 * or trimming it to a clip was web-only.
 *
 * A modal replaces all of that — name, volume and clip bounds in one form,
 * pre-filled with the current values, with no timer and nothing posted to the
 * channel. Blank fields are meaningful: leaving a clip bound empty clears it,
 * which is how a clipped intro is restored to the full track.
 *
 * The target intro id is encoded into the modal `customId`
 * (`editintro:<guildId>_<discordId>_<index>`) so no cross-interaction state is
 * needed. `DefaultModalManager` routes on the prefix before `:`.
 */
@Component
class EditIntroModal(
    private val introHelper: IntroHelper,
) : Modal {

    override val name = MODAL_NAME

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        val introId = event.modalId.substringAfter(':', "").takeIf { it.isNotBlank() }
            ?: return reply(ctx, "That edit form lost track of which intro it was for — run `/editintro` again.")

        // The modal is only ever shown to the user who opened it, but the id
        // travels through the client so re-derive ownership from it rather
        // than trusting it. Mirrors IntroWebService.requireOwnedIntro.
        val expectedPrefix = "${ctx.guild.idLong}_${event.user.idLong}_"
        if (!introId.startsWith(expectedPrefix)) {
            logger.warn { "Rejecting intro edit for '$introId' — not owned by user ${event.user.idLong}" }
            return reply(ctx, "That intro isn't yours to edit.")
        }

        val intro = introHelper.findIntroById(introId)
            ?: return reply(ctx, "Unable to find that intro — it may have been deleted.")

        val name = event.getValue(FIELD_NAME)?.asString?.trim()
        if (name != null && name.isEmpty()) return reply(ctx, "Name cannot be empty.")
        if (name != null && name.length > MAX_NAME_LENGTH) {
            return reply(ctx, "Name is too long (max $MAX_NAME_LENGTH characters).")
        }

        val rawVolume = event.getValue(FIELD_VOLUME)?.asString?.trim().orEmpty()
        val volume = if (rawVolume.isEmpty()) intro.introVolume else rawVolume.toIntOrNull()
        if (volume == null || volume !in 0..100) {
            return reply(ctx, "Volume must be a whole number between 0 and 100.")
        }

        val start = IntroClip.parseTimestamp(event.getValue(FIELD_START)?.asString, "Clip start")
        if (start is IntroClip.Parsed.Invalid) return reply(ctx, start.message)
        val end = IntroClip.parseTimestamp(event.getValue(FIELD_END)?.asString, "Clip end")
        if (end is IntroClip.Parsed.Invalid) return reply(ctx, end.message)

        val startMs = (start as IntroClip.Parsed.Ok).ms
        val endMs = (end as IntroClip.Parsed.Ok).ms

        val rawEnabled = event.getValue(FIELD_ENABLED)?.asString?.trim().orEmpty()
        val enabled = if (rawEnabled.isEmpty()) intro.enabled else parseEnabled(rawEnabled)
        if (enabled == null) {
            return reply(ctx, "Play on join must be `yes` or `no`.")
        }

        // Source duration is only knowable for URL intros; for uploads the
        // callback validates span/ordering alone, exactly as the web form does.
        val sourceUrl = intro.takeIf { IntroPresenter.isUrlIntro(it) }?.musicBlob?.let { String(it) }
        introHelper.validateClipAgainstSource(sourceUrl, startMs, endMs) { error ->
            if (error != null) {
                reply(ctx, error)
                return@validateClipAgainstSource
            }
            applyEdit(ctx, intro, name, volume, startMs, endMs, enabled)
        }
    }

    private fun applyEdit(
        ctx: ModalContext,
        intro: MusicDto,
        name: String?,
        volume: Int,
        startMs: Int?,
        endMs: Int?,
        enabled: Boolean,
    ) {
        name?.let { intro.fileName = it }
        intro.introVolume = volume
        intro.startMs = startMs
        intro.endMs = endMs
        intro.enabled = enabled
        introHelper.updateIntro(intro)

        logger.info { "Updated intro '${intro.id}' — ${IntroPresenter.summary(intro)}" }
        reply(
            ctx,
            "Updated **${IntroPresenter.displayName(intro)}** — ${IntroPresenter.summary(intro)}",
        )
    }

    private fun reply(ctx: ModalContext, message: String) {
        ctx.event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        const val MODAL_NAME = "editintro"
        const val FIELD_NAME = "intro_name"
        const val FIELD_VOLUME = "intro_volume"
        const val FIELD_START = "intro_start"
        const val FIELD_END = "intro_end"
        const val FIELD_ENABLED = "intro_enabled"

        private val TRUTHY = setOf("yes", "y", "true", "on", "1", "enabled")
        private val FALSEY = setOf("no", "n", "false", "off", "0", "disabled")

        /**
         * Discord modals can't mix text inputs with select menus, so the
         * on/off switch is a text field. Accept the spellings people actually
         * type rather than insisting on one.
         */
        fun parseEnabled(raw: String): Boolean? = when (raw.lowercase()) {
            in TRUTHY -> true
            in FALSEY -> false
            else -> null
        }

        /** Matches IntroWebService.updateIntroName so both surfaces agree. */
        const val MAX_NAME_LENGTH = 200

        /** Room for `mm:ss.s` and then some; nothing legitimate is longer. */
        private const val TIMESTAMP_FIELD_LIMIT = 12

        fun customId(introId: String) = "$MODAL_NAME:$introId"

        /**
         * Builds the editor pre-filled from [intro]. Every field is optional —
         * an untouched form submits the values it was opened with, so
         * "cancel by submitting" is a no-op rather than a wipe.
         */
        fun buildFor(intro: MusicDto): JdaModal {
            val nameField = TextInput.create(FIELD_NAME, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, MAX_NAME_LENGTH)
                .setValue(IntroPresenter.displayName(intro).take(MAX_NAME_LENGTH))
                .build()
            val volumeField = TextInput.create(FIELD_VOLUME, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, 3)
                .setValue((intro.introVolume ?: 100).toString())
                .build()
            val startField = TextInput.create(FIELD_START, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, TIMESTAMP_FIELD_LIMIT)
                .setPlaceholder("0:00")
                .apply { IntroClip.format(intro.startMs).takeIf { it.isNotEmpty() }?.let { setValue(it) } }
                .build()
            val endField = TextInput.create(FIELD_END, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, TIMESTAMP_FIELD_LIMIT)
                .setPlaceholder("0:${"%02d".format(IntroClip.MAX_DURATION_SECONDS)}")
                .apply { IntroClip.format(intro.endMs).takeIf { it.isNotEmpty() }?.let { setValue(it) } }
                .build()

            val enabledField = TextInput.create(FIELD_ENABLED, TextInputStyle.SHORT)
                .setRequired(false)
                .setRequiredRange(0, 8)
                .setPlaceholder("yes / no")
                .setValue(if (intro.enabled) "yes" else "no")
                .build()

            // Five components is Discord's modal cap — this form is now full.
            return JdaModal.create(customId(intro.id.orEmpty()), "Edit intro")
                .addComponents(
                    Label.of("Name", nameField),
                    Label.of("Volume (0-100)", volumeField),
                    Label.of("Clip start — blank for none", startField),
                    Label.of("Clip end — blank for none", endField),
                    Label.of("Play on join?", enabledField),
                )
                .build()
        }
    }
}
