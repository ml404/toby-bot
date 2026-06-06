package bot.toby.handler

import bot.toby.command.commands.mtg.CubeEmbeds
import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import common.discord.embed
import common.logging.DiscordLogger
import common.mtg.CubeCard
import common.mtg.MtgColor
import common.mtg.Rarity
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
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
 * resolve each on Scryfall and reply with the card images, rules text and a
 * back face for double-faced cards. This is the feature most MTG communities
 * install a bot for — it works in any channel with no slash command.
 *
 * Resolution is fuzzy (handles partial names), capped per message so a wall
 * of brackets can't spam the channel, and runs on [Dispatchers.IO]. A guild
 * can turn it off via the [ConfigDto.Configurations.CARD_MENTIONS] config
 * (opt-out: on unless explicitly set to "false").
 */
@Service
class CardMentionListener @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    private val configService: ConfigService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || event.isWebhookMessage) return
        if (event.isFromGuild && !mentionsEnabled(event.guild.idLong)) return
        val names = cardMentions(event.message.contentRaw)
        if (names.isEmpty()) return
        CoroutineScope(dispatcher).launch {
            runCatching {
                val embeds = names.mapNotNull { fetcher.fetchNamed(it) }
                    .flatMap(::cardEmbeds)
                    .take(MAX_EMBEDS)
                if (embeds.isNotEmpty()) event.channel.sendMessageEmbeds(embeds).queue()
            }.onFailure { logger.error("Card-mention lookup failed: $it") }
        }
    }

    /** False only when the guild has explicitly disabled card mentions. */
    private fun mentionsEnabled(guildId: Long): Boolean =
        configService.getConfigByName(ConfigDto.Configurations.CARD_MENTIONS.configValue, guildId.toString())
            ?.value?.lowercase() != "false"

    /** The front-face panel, plus a back-face image embed for a double-faced card. */
    private fun cardEmbeds(card: CubeCard): List<MessageEmbed> {
        val colours = if (card.colors.isEmpty()) "Colourless"
        else MtgColor.entries.filter { it in card.colors }.joinToString(", ") { it.displayName }
        val front = embed(color = GOLD) {
            setTitle(card.name)
            card.imageUrl?.let { setImage(it) }
            val facts = buildList {
                if (card.typeLine.isNotBlank()) add("**Type** · ${card.typeLine}")
                card.rarity?.let { add("**Rarity** · ${Rarity.parse(it).displayName}") }
                add("**Colour identity** · $colours")
                CubeEmbeds.priceLine(card)?.let { add("**Price** · $it") }
                if (card.legalFormats.isNotEmpty()) add("**Legal** · ${card.legalFormats.joinToString(", ")}")
            }.joinToString("\n")
            setDescription(facts + (card.oracleText?.let { "\n\n${CubeEmbeds.oracleBlock(it)}" }.orEmpty()))
        }
        val back = card.imageUrlBack?.let { url ->
            embed(color = GOLD) {
                setTitle("${card.name} (back)")
                setImage(url)
            }
        }
        return listOfNotNull(front, back)
    }

    companion object {
        /** Magic five-colour gold, matching the cube tooling's accent. */
        private val GOLD = Color(199, 161, 79)
        private val MENTION = Regex("\\[\\[([^\\[\\]]+)]]")

        /** Max cards resolved from one message, so a bracket-wall can't spam. */
        const val MAX_CARDS = 5

        /** Discord caps a message at 10 embeds (a DFC contributes two). */
        const val MAX_EMBEDS = 10

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
