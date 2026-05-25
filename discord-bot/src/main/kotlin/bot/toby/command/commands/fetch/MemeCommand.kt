package bot.toby.command.commands.fetch

import bot.toby.dto.web.RedditAPIDto
import bot.toby.dto.web.RedditAPIDto.TimePeriod
import com.google.gson.Gson
import com.google.gson.JsonParser
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStreamReader
import kotlin.random.Random

@Component
class MemeCommand : FetchCommand {
    companion object {
        const val SUBREDDIT = "subreddit"
        const val TIME_PERIOD = "timeperiod"
        const val LIMIT = "limit"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        try {
            logger.setGuildAndMemberContext(ctx.guild, ctx.member)
            handle(ctx, HttpClients.createDefault(), requestingUserDto, deleteDelay)
        } catch (e: IOException) {
            logger.error("IOException occurred while handling command: $e")
            throw RuntimeException(e)
        }
    }

    fun handle(
        ctx: CommandContext,
        httpClient: HttpClient,
        requestingUserDto: UserDto?,
        deleteDelay: Int
    ) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        if (requestingUserDto?.memePermission != true) {
            logger.info("User ${event.member?.effectiveName} does not have meme permission")
            sendErrorMessage(event, deleteDelay)
            return
        }

        val subredditArg = event.getOption(SUBREDDIT)?.asString
        val timePeriod = TimePeriod.parseTimePeriod(event.getOption(TIME_PERIOD)?.asString ?: "day").timePeriod
        val limit = event.getOption(LIMIT)?.asInt ?: 5
        logger.info { "Reddit API args - Subreddit: $subredditArg, Time Period: $timePeriod, Limit: $limit" }

        fetch(event.hook, httpClient, subredditArg, timePeriod, limit, deleteDelay)
    }

    /**
     * Shared entry point so [bot.toby.button.buttons.MemeButton] can
     * re-roll against the same subreddit/timeperiod/limit without
     * re-parsing slash-command options. The caller must have already
     * acknowledged the interaction (`deferReply()` for the slash
     * command, `deferEdit()` for the button) so the hook is ready to
     * edit the original message.
     */
    fun fetch(
        hook: InteractionHook,
        httpClient: HttpClient,
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

        val outcome = fetchRedditPost(subreddit, timePeriod, limit, httpClient)
        when (outcome) {
            is RedditOutcome.Embed -> {
                logger.info("Successfully fetched meme from subreddit: $subreddit")
                val edit = hook.editOriginalEmbeds(listOf(outcome.embed))
                val row = subreddit?.let { MemeEmbeds.rerollRow(it, timePeriod, limit) }
                if (row != null) edit.setComponents(row)
                edit.queue { scheduleDelete(hook, deleteDelay) }
            }
            is RedditOutcome.Error -> {
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

    private sealed interface RedditOutcome {
        data class Embed(val embed: MessageEmbed) : RedditOutcome
        data class Error(val message: String) : RedditOutcome
    }

    private fun fetchRedditPost(
        subreddit: String?,
        timePeriod: String,
        limit: Int,
        httpClient: HttpClient,
    ): RedditOutcome {
        val gson = Gson()
        val redditApiUrl = String.format(RedditAPIDto.REDDIT_PREFIX, subreddit, limit, timePeriod)
        val request = HttpGet(redditApiUrl)
        logger.info("Fetching Reddit post from URL: $redditApiUrl")
        val response = httpClient.execute(request)
        if (response.statusLine.statusCode != 200) {
            logger.error("Error response from Reddit API: ${response.statusLine.statusCode}")
            return RedditOutcome.Error("Failed to fetch meme. Please try again later.")
        }

        val jsonResponse = JsonParser.parseReader(InputStreamReader(response.entity.content)).asJsonObject
        EntityUtils.consume(response.entity)

        val children = jsonResponse.getAsJsonObject("data").getAsJsonArray("children")
        if (children.size() == 0) {
            logger.info("No memes found in subreddit: $subreddit")
            return RedditOutcome.Error("No memes found in r/${subreddit ?: "?"}.")
        }

        val meme = children[Random.nextInt(children.size())].asJsonObject.getAsJsonObject("data")
        val redditAPIDto = gson.fromJson(meme.toString(), RedditAPIDto::class.java)

        if (redditAPIDto.isNsfw == true) {
            logger.warn("NSFW meme detected from subreddit: $subreddit")
            return RedditOutcome.Error(
                "I pulled back a NSFW post — either the subreddit's flagged or Reddit picked a bad one. Skipped."
            )
        }
        if (redditAPIDto.video == true) {
            logger.warn("Video meme detected from subreddit: $subreddit")
            return RedditOutcome.Error("I pulled back a video, whoops. Try again maybe? Or not, up to you.")
        }

        val title = meme["title"].asString
        val url = meme["url"].asString
        val author = meme["author"].asString
        logger.info("Meme fetched successfully - Title: $title, Author: $author, URL: $url")

        return RedditOutcome.Embed(
            MemeEmbeds.resultEmbed(title, url, author, subreddit ?: "?"),
        )
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
