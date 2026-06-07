package bot.toby.command.commands.mtg

import bot.toby.helpers.stringOption
import common.mtg.CubeCard
import common.mtg.MtgCommandRef
import common.mtg.DeckLegality
import core.command.CommandContext
import database.dto.user.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/mtgdeck` — deck-level analysis. Today: `legality`, which checks a saved cube
 * (or a Scryfall query) against a format and flags banned / not-in-format /
 * restricted cards.
 */
@Component
class DeckCommand @Autowired constructor(
    private val resolver: MtgPoolResolver,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractMtgCommand(dispatcher) {

    override val name: String = MtgCommandRef.DECK
    override val description: String = "Analyse a Magic deck — check its legality in a format."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_LEGALITY -> launchHandling(ctx) { handleLegality(ctx, requestingUserDto, deleteDelay) }
            else -> replyError(ctx, "Pick a subcommand: legality.", deleteDelay)
        }
    }

    /** Checks a saved cube / queried pool against a format's banned & legality list. */
    private suspend fun handleLegality(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val formatKey = ctx.event.stringOption(OPT_FORMAT)?.trim()?.lowercase()
        val format = CubeCard.FORMATS.firstOrNull { it.first == formatKey }
        if (format == null) {
            replyError(ctx, "Pick a `format` to check against (e.g. Modern, Commander).", deleteDelay)
            return
        }
        val resolved = resolver.resolve(ctx.event.stringOption(OPT_SAVED), ctx.event.stringOption(OPT_QUERY), requestingUserDto.discordId)
        when (resolved) {
            is MtgPoolResolver.PoolResult.Failed -> replyError(ctx, resolved.message, deleteDelay)
            is MtgPoolResolver.PoolResult.Ready -> {
                val report = DeckLegality.check(resolved.pool, format.first)
                reply(ctx, CubeEmbeds.legalityEmbed(report, format.second, resolved.label, resolved.notFound), deleteDelay)
            }
        }
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_LEGALITY, "Check whether a saved cube/deck is legal in a format (banned & not-legal cards).")
            .addOptions(
                OptionData(OptionType.STRING, OPT_FORMAT, "Format to check against.", true)
                    .addChoices(CubeCard.FORMATS.map { net.dv8tion.jda.api.interactions.commands.Command.Choice(it.second, it.first) }),
                OptionData(OptionType.STRING, OPT_SAVED, "Name of a deck/cube you saved on the website.", false)
                    .setAutoComplete(true),
                OptionData(OptionType.STRING, OPT_QUERY, "Or a Scryfall search to check instead (e.g. set:mh3).", false),
            ),
    )

    companion object {
        const val SUB_LEGALITY = MtgCommandRef.Deck.LEGALITY

        const val OPT_FORMAT = "format"
        const val OPT_SAVED = "saved"
        const val OPT_QUERY = "query"
    }
}
