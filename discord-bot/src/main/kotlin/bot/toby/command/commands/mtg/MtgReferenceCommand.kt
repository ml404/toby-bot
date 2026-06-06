package bot.toby.command.commands.mtg

import bot.toby.helpers.stringOption
import common.mtg.MtgCommandRef
import common.mtg.MtgGlossary
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
 * `/mtg` — quick reference: look up a set by code (`set`) or a keyword's
 * reminder text (`rule`). Beginner-friendly, not tied to cube building.
 */
@Component
class MtgReferenceCommand @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractMtgCommand(dispatcher) {

    override val name: String = MtgCommandRef.MTG
    override val description: String = "Magic quick reference — set info and keyword reminder text."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_SET -> launchHandling(ctx) { handleSet(ctx, deleteDelay) }
            SUB_RULE -> handleRule(ctx, deleteDelay) // static glossary, no IO
            else -> reply(ctx, CubeEmbeds.errorEmbed("Pick a subcommand: set or rule."), deleteDelay)
        }
    }

    /** Looks up a Magic set by its code on Scryfall. */
    private suspend fun handleSet(ctx: CommandContext, deleteDelay: Int) {
        val code = ctx.event.stringOption(OPT_CODE)?.trim()
        if (code.isNullOrEmpty()) {
            reply(ctx, CubeEmbeds.errorEmbed("Give me a set `code` (e.g. vow, mh3)."), deleteDelay)
            return
        }
        when (val set = fetcher.fetchSet(code)) {
            null -> reply(ctx, CubeEmbeds.errorEmbed("Couldn't find a set with code `$code`."), deleteDelay)
            else -> reply(ctx, CubeEmbeds.setEmbed(set), deleteDelay)
        }
    }

    /** Looks up a keyword's reminder text in the built-in glossary (no network). */
    private fun handleRule(ctx: CommandContext, deleteDelay: Int) {
        val term = ctx.event.stringOption(OPT_TERM)?.trim()
        if (term.isNullOrEmpty()) {
            reply(ctx, CubeEmbeds.errorEmbed("Give me a keyword (e.g. trample, deathtouch)."), deleteDelay)
            return
        }
        when (val found = MtgGlossary.lookup(term)) {
            null -> reply(ctx, CubeEmbeds.errorEmbed("No glossary entry for `$term`. Try an evergreen keyword like trample or flying."), deleteDelay)
            else -> reply(ctx, CubeEmbeds.ruleEmbed(found), deleteDelay)
        }
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_SET, "Look up a Magic set by its code (release date, card count).")
            .addOptions(OptionData(OptionType.STRING, OPT_CODE, "The set code (e.g. vow, mh3).", true)),
        SubcommandData(SUB_RULE, "Look up a Magic keyword's reminder text (e.g. trample, flying).")
            .addOptions(OptionData(OptionType.STRING, OPT_TERM, "The keyword to explain.", true)),
    )

    companion object {
        const val SUB_SET = MtgCommandRef.Reference.SET
        const val SUB_RULE = MtgCommandRef.Reference.RULE

        const val OPT_CODE = "code"
        const val OPT_TERM = "term"
    }
}
