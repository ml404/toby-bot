package bot.toby.command.commands.misc

import common.discord.embed
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Embed + button factories shared between [EightBallCommand] and the
 * `EightBallButton` re-ask flow. Keeping them in one place so the two
 * entry points stay visually identical without copy-pasting builders.
 */
internal object EightBallEmbeds {
    const val BUTTON_NAME = "8ball"
    const val ASK_AGAIN_COMPONENT_ID = "$BUTTON_NAME:ask"

    /** Dark indigo — "the ball is shaking, anticipate the answer". */
    val SHAKE_COLOR: Color = Color(60, 30, 120)

    /** Discord blurple — the website's accent. Matches /utils. */
    val ANSWER_COLOR: Color = Color(88, 101, 242)

    /** Tom's punishment branch — same red as Discord errors. */
    val TOM_COLOR: Color = Color(237, 66, 69)

    fun shakeEmbed(): MessageEmbed = embed(color = SHAKE_COLOR) {
        setAuthor("🎱  The Magic 8-Ball")
        setTitle("Shaking…")
        setDescription("*the answer is forming…*")
    }

    fun answerEmbed(response: String, askedBy: String): MessageEmbed = embed(color = ANSWER_COLOR) {
        setAuthor("🎱  The Magic 8-Ball")
        setTitle("…it says:")
        setDescription("> **$response**.")
        setFooter("Asked by $askedBy")
    }

    fun tomEmbed(deductedSocialCredit: Int): MessageEmbed = embed(color = TOM_COLOR) {
        setAuthor("🎱  The Magic 8-Ball")
        setTitle("Don't fucking talk to me.")
        setDescription("> Deducted **$deductedSocialCredit** social credit.")
    }

    fun askAgainRow(): ActionRow = ActionRow.of(
        Button.secondary(ASK_AGAIN_COMPONENT_ID, "Ask again")
    )
}
