package bot.toby.button.buttons.music

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.intro.IntroOwnership
import bot.toby.intro.IntroPresenter
import common.intro.IntroClip
import core.button.Button
import core.button.ButtonContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.buttons.Button as JdaButton
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Guild
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Plays one named intro, from the **View intros** context menu.
 *
 * There is a button per intro rather than a single "play theirs". The menu
 * lists somebody's intros by name; offering one button that then played a
 * *random* one of them was a small betrayal of the list right above it — with
 * three slots you had a one-in-three chance of hearing the one you were
 * looking at. The rotation is the right behaviour when the caller means "their
 * intro" (a voice join, `/play intro`) and the wrong behaviour when they have
 * pointed at one.
 *
 * The intro's id rides in the component id, since the id is
 * `guildId_discordId_slot` and so already says everything needed — and the
 * message it sits on is ephemeral, so there is nothing else left to read.
 *
 * Same permission story as `/play intro user:`: previewing is strictly less
 * intrusive than the surprise version everyone already gets on join. It does
 * still check `musicPermission` and the voice-channel state, because those
 * govern *making the bot play something*, which is a different question from
 * whose intro it is.
 */
@Component
class PlayIntroButton @Autowired constructor(
    private val introHelper: IntroHelper,
) : Button {

    override val name: String = BUTTON_NAME
    override val description: String = "Play a member's intro."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        if (!requestingUserDto.musicPermission) {
            return reply(ctx, "You don't have permission to make me play things.")
        }

        val guild = ctx.guild
        val introId = event.componentId.substringAfter(':').takeIf { it.isNotBlank() }
        // The id arrives from the client, so being offered it earlier proves
        // nothing about the one that came back.
        if (!IntroOwnership.inGuild(introId, guild.idLong)) {
            return reply(ctx, "That button has lost track of which intro it was for — open the menu again.")
        }

        val intro = introId?.let { introHelper.findIntroById(it) }
            ?: return reply(ctx, "That intro has gone — it may have been deleted since you opened this.")

        invalidVoiceState(ctx, guild)?.let { return reply(ctx, it) }

        val name = IntroPresenter.displayName(intro)

        MusicPlayerHelper.playIntro(intro, guild, deleteDelay = deleteDelay, member = ctx.member)
            ?: return reply(ctx, "Couldn't load **$name** just now — it may have stopped working.")

        logger.info { "Played intro '${intro.id}' on request from ${event.user.idLong}" }
        // No owner name: resolving one means a member-cache lookup that
        // returns null for anyone not cached, and the reply is ephemeral and
        // attached to the menu they just opened, so whose it is isn't in doubt.
        reply(ctx, "Playing **${describe(intro)}** — intro #${intro.index ?: '?'}.")
    }

    /**
     * Mirrors the check every music slash command makes, minus the reply: the
     * bot has to be in a channel to play into, and the caller has to be in it
     * to have any business starting audio there.
     *
     * @return the reason it can't play, or null when it can.
     */
    private fun invalidVoiceState(ctx: ButtonContext, guild: Guild): String? {
        val selfChannel = guild.selfMember.voiceState?.channel
            ?: return "I need to be in a voice channel for this to work."
        val memberChannel = ctx.member?.voiceState?.channel
            ?: return "You need to be in a voice channel for this to work."
        if (memberChannel != selfChannel) return "You need to be in the same voice channel as me for this to work."
        return null
    }

    private fun reply(ctx: ButtonContext, message: String) {
        ctx.event.hook.sendMessage(message).setEphemeral(true).queue()
    }

    companion object {
        const val BUTTON_NAME = "playintro"

        /** Keeps three buttons and a link from crowding one action row. */
        private const val LABEL_LIMIT = 24

        /**
         * A play button for one intro.
         *
         * Intros that sit out the rotation — switched off, or repeatedly
         * failing to load — are styled quietly rather than hidden. Hearing one
         * on purpose is exactly how you'd answer "why doesn't this play?", and
         * the embed above already says which is which.
         */
        fun button(intro: MusicDto): JdaButton = JdaButton.of(
            if (intro.enabled && !IntroPresenter.isBroken(intro)) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
            "$BUTTON_NAME:${intro.id.orEmpty()}",
            "▶ ${label(intro)}",
        )

        private fun label(intro: MusicDto): String {
            val name = IntroPresenter.displayName(intro)
            return if (name.length <= LABEL_LIMIT) name else name.take(LABEL_LIMIT - 1).trimEnd() + "…"
        }

        /** Describes what a button will play, for the confirmation message. */
        internal fun describe(intro: MusicDto): String =
            "${IntroPresenter.displayName(intro)} (${IntroClip.describe(intro.startMs, intro.endMs)})"
    }
}
