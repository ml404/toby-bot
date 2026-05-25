package bot.toby.command.commands.fetch

import common.discord.embed
import common.discord.field
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Embed + button factories for `/meme`. Matches the visual grammar of
 * the other utility-command polish (`EightBallEmbeds`, `RandomEmbeds`,
 * `RollEmbeds`) so the four commands feel like a set.
 */
internal object MemeEmbeds {
    const val BUTTON_NAME = "meme"
    private const val REROLL_PREFIX = "$BUTTON_NAME:reroll:"

    /** Discord's hard limit on a button's custom id. */
    private const val CUSTOM_ID_MAX = 100

    /** Discord blurple — the success / "here's your meme" state. */
    val OK_COLOR: Color = Color(88, 101, 242)

    /** Discord red — fetch failures, NSFW filter trips, etc. */
    val ERROR_COLOR: Color = Color(237, 66, 69)

    /** Muted purple — the "I'm working on it" intermediate state. */
    val LOADING_COLOR: Color = Color(60, 30, 120)

    fun loadingEmbed(subreddit: String?): MessageEmbed = embed(color = LOADING_COLOR) {
        setAuthor("🖼  Meme fetcher")
        setTitle("Fetching from r/${subreddit ?: "?"}…")
        setDescription("*hang tight, talking to Reddit…*")
    }

    fun resultEmbed(title: String, url: String, author: String, subreddit: String): MessageEmbed =
        embed(color = OK_COLOR) {
            setAuthor("🖼  Meme fetcher")
            setTitle(title, url)
            setImage(url)
            field(name = "subreddit", value = "r/$subreddit", inline = true)
            field(name = "posted by", value = "u/$author", inline = true)
        }

    fun errorEmbed(message: String): MessageEmbed = embed(color = ERROR_COLOR) {
        setAuthor("🖼  Meme fetcher")
        setTitle("Couldn't fetch a meme")
        setDescription(message)
    }

    /**
     * Encodes the original meme args into the reroll button id so the
     * handler can re-fetch with the same subreddit + filters without an
     * in-memory session map. Returns `null` if the encoded id would
     * exceed Discord's 100-char custom-id ceiling — caller should then
     * omit the button rather than silently truncate.
     */
    fun rerollRow(subreddit: String, timePeriod: String, limit: Int): ActionRow? {
        val componentId = "$REROLL_PREFIX$subreddit:$timePeriod:$limit"
        if (componentId.length > CUSTOM_ID_MAX) return null
        return ActionRow.of(Button.secondary(componentId, "Re-roll"))
    }

    /** Inverse of [rerollRow] for [bot.toby.button.buttons.MemeButton]. */
    data class RerollArgs(val subreddit: String, val timePeriod: String, val limit: Int)

    fun decodeReroll(componentId: String): RerollArgs? {
        val raw = componentId.removePrefix(REROLL_PREFIX)
        // Split into exactly 3 parts from the right so a subreddit
        // containing colons (Reddit doesn't actually allow that, but
        // belt-and-braces) wouldn't fragment the args.
        val parts = raw.split(":")
        if (parts.size < 3) return null
        val limit = parts.last().toIntOrNull() ?: return null
        val timePeriod = parts[parts.size - 2]
        val subreddit = parts.dropLast(2).joinToString(":")
        if (subreddit.isEmpty()) return null
        return RerollArgs(subreddit, timePeriod, limit)
    }
}
