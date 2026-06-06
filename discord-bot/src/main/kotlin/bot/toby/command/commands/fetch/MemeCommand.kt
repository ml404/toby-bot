package bot.toby.command.commands.fetch

import bot.toby.dto.web.RedditAPIDto.TimePeriod
import core.command.CommandContext
import database.dto.user.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class MemeCommand @Autowired constructor(
    private val fetcher: RedditMemeFetcher,
    // Mirrors /dnd and /mtgcube: the Reddit fetch runs on this dispatcher
    // (tests pass Dispatchers.Unconfined so the launched work resolves
    // synchronously).
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FetchCommand {

    companion object {
        const val SUBREDDIT = "subreddit"
        const val TIME_PERIOD = "timeperiod"
        const val LIMIT = "limit"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        if (requestingUserDto.memePermission != true) {
            logger.info("User ${event.member?.effectiveName} does not have meme permission")
            sendErrorMessage(event, deleteDelay)
            return
        }

        val subredditArg = event.getOption(SUBREDDIT)?.asString
        val timePeriod = TimePeriod.parseTimePeriod(event.getOption(TIME_PERIOD)?.asString ?: "day").timePeriod
        val limit = event.getOption(LIMIT)?.asInt ?: 5
        logger.info { "Reddit API args - Subreddit: $subredditArg, Time Period: $timePeriod, Limit: $limit" }

        fetchAsync(event.hook, subredditArg, timePeriod, limit, deleteDelay)
    }

    /**
     * Shared entry point so [bot.toby.button.buttons.misc.MemeButton] can
     * re-roll against the same subreddit/timeperiod/limit without re-parsing
     * slash-command options. The caller must have already acknowledged the
     * interaction (`deferReply()` for the slash command, `deferEdit()` for
     * the button) so the hook is ready to edit the original message.
     *
     * The fetch runs on [dispatcher] (mirroring the `/dnd` query handler) so
     * the blocking network work never ties up the gateway or the CPU-bound
     * default dispatcher; an unexpected failure still resolves the message
     * with an error embed.
     */
    fun fetchAsync(
        hook: InteractionHook,
        subreddit: String?,
        timePeriod: String,
        limit: Int,
        deleteDelay: Int,
    ) {
        CoroutineScope(dispatcher).launch {
            runCatching { fetch(hook, subreddit, timePeriod, limit, deleteDelay) }
                .onFailure { e ->
                    logger.error("Meme fetch failed for subreddit '$subreddit': $e")
                    editError(hook, "Something went wrong fetching that meme. Try again in a moment.", deleteDelay)
                }
        }
    }

    private suspend fun fetch(
        hook: InteractionHook,
        subreddit: String?,
        timePeriod: String,
        limit: Int,
        deleteDelay: Int,
    ) {
        hook.editOriginalEmbeds(listOf(MemeEmbeds.loadingEmbed(subreddit))).queue()

        if (subreddit == "sneakybackgroundfeet") {
            logger.info("Requested subreddit 'sneakybackgroundfeet'")
            editError(hook, "Don't talk to me.", deleteDelay)
            return
        }

        when (val outcome = fetcher.fetch(subreddit, timePeriod, limit)) {
            is RedditMemeFetcher.Result.Success -> {
                logger.info("Successfully fetched meme from subreddit: $subreddit")
                val edit = hook.editOriginalEmbeds(
                    listOf(MemeEmbeds.resultEmbed(outcome.title, outcome.url, outcome.author, subreddit ?: "?")),
                )
                val row = subreddit?.let { MemeEmbeds.rerollRow(it, timePeriod, limit) }
                if (row != null) edit.setComponents(row)
                edit.queue { scheduleDelete(hook, deleteDelay) }
            }
            is RedditMemeFetcher.Result.Error -> {
                logger.warn("Failed to fetch meme from subreddit: $subreddit — ${outcome.message}")
                editError(hook, outcome.message, deleteDelay)
            }
        }
    }

    private fun editError(hook: InteractionHook, message: String, deleteDelay: Int) {
        hook.editOriginalEmbeds(listOf(MemeEmbeds.errorEmbed(message))).queue {
            scheduleDelete(hook, deleteDelay)
        }
    }

    private fun scheduleDelete(hook: InteractionHook, deleteDelay: Int) {
        if (deleteDelay > 0) {
            hook.deleteOriginal().queueAfter(
                deleteDelay.toLong(),
                java.util.concurrent.TimeUnit.SECONDS,
            )
        }
    }

    override val name: String get() = "meme"
    override val description: String get() = "This command shows a meme from the subreddit you've specified (SFW only)"
    override val optionData: List<OptionData>
        get() {
            val subreddit = OptionData(OptionType.STRING, SUBREDDIT, "Which subreddit to pull the meme from", true)
            val timePeriod = OptionData(
                OptionType.STRING,
                TIME_PERIOD,
                "What time period filter to apply to the subreddit (e.g. day/week/month/all). Default day.",
                false,
            )
            TimePeriod.entries.forEach { tp -> timePeriod.addChoice(tp.timePeriod, tp.timePeriod) }
            val limit = OptionData(OptionType.INTEGER, LIMIT, "Pick from top X posts of that day. Default 5.", false)
            return listOf(subreddit, timePeriod, limit)
        }
}
