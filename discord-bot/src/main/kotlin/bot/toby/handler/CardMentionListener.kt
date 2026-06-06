package bot.toby.handler

import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import common.discord.embed
import common.logging.DiscordLogger
import common.mtg.CubeCard
import common.mtg.MtgColor
import common.mtg.Rarity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.awt.Color

/**
 * Inline Magic card lookups: when a message contains `[[Card Name]]` markers,
 * resolve each on Scryfall and reply with the card images. This is the
 * feature most MTG Discord communities install a bot for — it works in any
 * channel with no slash command.
 *
 * Resolution is fuzzy (handles partial/approximate names), capped per message
 * so a wall of brackets can't spam the channel, and runs on [Dispatchers.IO]
 * so the gateway thread is never blocked. Names that don't resolve are
 * silently skipped.
 */
@Service
class CardMentionListener @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || event.isWebhookMessage) return
        val names = cardMentions(event.message.contentRaw)
        if (names.isEmpty()) return
        CoroutineScope(dispatcher).launch {
            runCatching {
                val embeds = names.mapNotNull { name -> fetcher.fetchNamed(name)?.let(::cardEmbed) }
                if (embeds.isNotEmpty()) event.channel.sendMessageEmbeds(embeds).queue()
            }.onFailure { logger.error("Card-mention lookup failed: $it") }
        }
    }

    /** A compact card panel: the image plus a one-line set of facts. */
    private fun cardEmbed(card: CubeCard): MessageEmbed = embed(color = GOLD) {
        setTitle(card.name)
        card.imageUrl?.let { setImage(it) }
        val colours = if (card.colors.isEmpty()) "Colourless"
        else MtgColor.entries.filter { it in card.colors }.joinToString(", ") { it.displayName }
        setDescription(
            buildList {
                if (card.typeLine.isNotBlank()) add("**Type** · ${card.typeLine}")
                card.rarity?.let { add("**Rarity** · ${Rarity.parse(it).displayName}") }
                add("**Colour identity** · $colours")
            }.joinToString("\n")
        )
    }

    companion object {
        /** Magic five-colour gold, matching the cube tooling's accent. */
        private val GOLD = Color(199, 161, 79)
        private val MENTION = Regex("\\[\\[([^\\[\\]]+)]]")

        /** Max cards resolved from one message, so a bracket-wall can't spam. */
        const val MAX_CARDS = 5

        /** The distinct, non-blank `[[name]]` mentions in a message, capped at [MAX_CARDS]. */
        fun cardMentions(content: String): List<String> =
            MENTION.findAll(content)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_CARDS)
                .toList()
    }
}
