package bot.toby.command.commands.economy

import core.command.CommandContext
import database.dto.UserDto
import database.economy.Keno
import database.service.KenoService
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/keno stake:<int> [spots:<int>] [picks:<csv>]` — one-shot keno
 * round. If `picks` is omitted, the bot quick-picks `spots` numbers (so
 * a low-effort "/keno stake:50" works fine); if `picks` is supplied,
 * its count must match `spots`. `spots` defaults to 5.
 *
 * No buttons: keno resolves in a single slash-command call so there is
 * no anchor-then-confirm split. The same path the web
 * `/casino/{guildId}/keno` page uses — both routes call through
 * [KenoService.play] so the paytable, pool size, and balance debit/
 * credit semantics stay consistent.
 */
@Component
class KenoCommand @Autowired constructor(
    private val kenoService: KenoService
) : EconomyCommand {

    override val name: String = "keno"
    override val description: String =
        "Pick ${Keno.MIN_SPOTS}-${Keno.MAX_SPOTS} numbers from 1-${Keno.POOL_SIZE}; bot draws ${Keno.DRAWS}. " +
            "Bet ${Keno.MIN_STAKE}-${Keno.MAX_STAKE} credits."

    companion object {
        private const val OPT_STAKE = "stake"
        private const val OPT_SPOTS = "spots"
        private const val OPT_PICKS = "picks"
        private const val DEFAULT_SPOTS = 5
        private const val TITLE = "🎯 Keno"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(
            OptionType.INTEGER, OPT_STAKE,
            "Credits to wager (${Keno.MIN_STAKE}-${Keno.MAX_STAKE})", true
        )
            .setMinValue(Keno.MIN_STAKE)
            .setMaxValue(Keno.MAX_STAKE),
        OptionData(
            OptionType.INTEGER, OPT_SPOTS,
            "How many numbers to pick (${Keno.MIN_SPOTS}-${Keno.MAX_SPOTS}, default $DEFAULT_SPOTS)", false
        )
            .setMinValue(Keno.MIN_SPOTS.toLong())
            .setMaxValue(Keno.MAX_SPOTS.toLong()),
        OptionData(
            OptionType.STRING, OPT_PICKS,
            "Your numbers (comma-separated, 1-${Keno.POOL_SIZE}). Omit to quick-pick.", false
        )
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "This command can only be used in a server.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "You must specify a stake.", deleteDelay); return
        }
        val spots = event.getOption(OPT_SPOTS)?.asLong?.toInt() ?: DEFAULT_SPOTS

        val picks = parsePicks(event.getOption(OPT_PICKS)?.asString, spots) ?: run {
            WagerCommandEmbeds.replyError(
                event, TITLE,
                "Picks must be $spots distinct numbers between 1 and ${Keno.POOL_SIZE}, comma-separated. " +
                    "Omit the option to quick-pick.",
                deleteDelay
            ); return
        }

        val outcome = kenoService.play(
            requestingUserDto.discordId, guild.idLong, stake, picks
        )
        WagerCommandEmbeds.reply(event, KenoEmbeds.outcomeEmbed(outcome), deleteDelay)
    }

    /**
     * Either parse a user-supplied CSV into a list of distinct in-pool
     * numbers, or return a quick-pick of [spots] when [raw] is null /
     * blank. Returns null on a CSV that doesn't validate (count
     * mismatch, out-of-range value, duplicate, non-integer) so the
     * caller can surface a uniform error to the user.
     */
    internal fun parsePicks(raw: String?, spots: Int): List<Int>? {
        if (raw.isNullOrBlank()) {
            // Quick-pick path. The service will reject if spots is out
            // of range; we still bound here so the in-engine require()
            // doesn't surface as an opaque IllegalArgumentException.
            if (spots !in Keno.MIN_SPOTS..Keno.MAX_SPOTS) return null
            return kenoService.quickPick(spots)
        }
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != spots) return null
        val parsed = parts.mapNotNull { it.toIntOrNull() }
        if (parsed.size != parts.size) return null
        if (parsed.toSet().size != parsed.size) return null
        if (parsed.any { it !in Keno.POOL_RANGE }) return null
        return parsed
    }
}
