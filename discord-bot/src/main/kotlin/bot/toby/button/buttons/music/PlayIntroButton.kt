package bot.toby.button.buttons.music

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.intro.IntroPresenter
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.buttons.Button as JdaButton
import net.dv8tion.jda.api.entities.Guild
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Plays a member's intro on demand, from the **View intros** context menu.
 *
 * The target's id rides in the component id (`playintro:1234`) because the
 * button outlives the interaction that built it, and the message it sits on is
 * ephemeral — there is nothing else left to read the target off.
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
        val targetId = event.componentId.substringAfter(':').toLongOrNull()
            ?: return reply(ctx, "That button has lost track of whose intro it was for — try the menu again.")

        invalidVoiceState(ctx, guild)?.let { return reply(ctx, it) }

        val targetDto = introHelper.findUserById(targetId, guild.idLong)
        val targetName = guild.getMemberById(targetId)?.effectiveName ?: "They"
        if (targetDto.musicDtos.none { it.enabled }) {
            return reply(ctx, "$targetName has nothing playable set right now.")
        }

        val played = MusicPlayerHelper.playUserIntro(
            targetDto, guild, deleteDelay = deleteDelay, member = guild.getMemberById(targetId),
        ) ?: return reply(ctx, "Couldn't load $targetName's intro just now — it may have stopped working.")

        logger.info { "Played $targetId's intro '${played.id}' on request from ${event.user.idLong}" }
        reply(ctx, "Playing **${IntroPresenter.displayName(played)}** — $targetName's intro.")
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

        fun button(targetDiscordId: Long, isSelf: Boolean): JdaButton = JdaButton.primary(
            "$BUTTON_NAME:$targetDiscordId",
            if (isSelf) "▶ Play mine" else "▶ Play theirs",
        )
    }
}
