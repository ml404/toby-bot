package bot.toby.command.commands.misc

import common.discord.embed
import common.discord.field
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Embed + button factories for the `/random` reel. Mirrors the visual
 * grammar of [EightBallEmbeds] so the misc commands feel like a set.
 */
internal object RandomEmbeds {
    const val BUTTON_NAME = "random"
    const val PICK_AGAIN_PREFIX = "$BUTTON_NAME:pick:"

    /** Discord's hard limit on a button's custom id. */
    private const val CUSTOM_ID_MAX = 100

    /** Discord blurple — keeps the misc-command palette consistent. */
    val WHEEL_COLOR: Color = Color(88, 101, 242)

    /** Muted grey for the "no options provided" empty state. */
    val EMPTY_COLOR: Color = Color(160, 160, 176)

    fun wheelEmbed(winner: String, options: List<String>, askedBy: String): MessageEmbed =
        embed(color = WHEEL_COLOR) {
            setAuthor("🎲  Random chooser")
            setTitle("The wheel picked…")
            setDescription("> **$winner**")
            if (options.size > 1) {
                field(
                    name = "Pool of ${options.size}",
                    value = options.joinToString(" • "),
                    inline = false,
                )
            }
            setFooter("Asked by $askedBy")
        }

    fun noOptionsEmbed(description: String): MessageEmbed =
        embed(color = EMPTY_COLOR) {
            setAuthor("🎲  Random chooser")
            setTitle("Nothing to pick from")
            setDescription(description)
        }

    /**
     * Encodes the options list into a `random:pick:<csv>` button id so
     * the re-roll handler can deserialize without an in-memory registry.
     * Returns `null` if the encoded id wouldn't fit Discord's 100-char
     * custom-id ceiling — the caller should then omit the button rather
     * than truncate (silently dropping options would mislead users).
     */
    fun pickAgainRow(options: List<String>): ActionRow? {
        val encoded = URLEncoder.encode(options.joinToString(","), StandardCharsets.UTF_8)
        val componentId = PICK_AGAIN_PREFIX + encoded
        if (componentId.length > CUSTOM_ID_MAX) return null
        return ActionRow.of(Button.secondary(componentId, "Pick another"))
    }

    /** Inverse of [pickAgainRow] — used by the button handler. */
    fun decodeOptions(componentId: String): List<String> {
        val raw = componentId.removePrefix(PICK_AGAIN_PREFIX)
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8)
        return decoded.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
