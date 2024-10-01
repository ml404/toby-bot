package toby.command.commands.fetch

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.dto.web.RedditAPIDto
import toby.dto.web.RedditAPIDto.TimePeriod
import toby.jpa.dto.UserDto
import java.io.IOException
import java.io.InputStreamReader
import kotlin.random.Random

class MemeCommand : IFetchCommand {
    private val SUBREDDIT = "subreddit"
    private val TIME_PERIOD = "timeperiod"
    private val LIMIT = "limit"

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        try {
            logger.setGuildAndMemberContext(ctx.guild, ctx.member)
            handle(ctx, HttpClients.createDefault(), requestingUserDto, deleteDelay)
        } catch (e: IOException) {
            logger.error("IOException occurred while handling command: $e")
            throw RuntimeException(e)
        }
    }

    @Throws(IOException::class)
    fun handle(ctx: CommandContext, httpClient: HttpClient, requestingUserDto: UserDto?, deleteDelay: Int?) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        event.deferReply().queue()

        if (requestingUserDto?.memePermission != true) {
            logger.info("User ${event.member?.effectiveName} does not have meme permission")
            sendErrorMessage(event, deleteDelay ?: 0)
            return
        }

        val result = getRedditApiArgs(event)
        val subredditArg = result.subredditArg

        if (subredditArg == "sneakybackgroundfeet") {
            logger.info("Requested subreddit 'sneakybackgroundfeet'")
            event.hook.sendMessageFormat("Don't talk to me.").queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
        } else {
            val embed = fetchRedditPost(result, event, deleteDelay ?: 0, httpClient)
            if (embed != null) {
                logger.info("Successfully fetched meme from subreddit: $subredditArg")
                event.hook.sendMessageEmbeds(embed).queue()
            } else {
                logger.warn("Failed to fetch meme from subreddit: $subredditArg")
                event.hook.sendMessage("Failed to fetch meme. Please try again later.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
            }
        }
    }

    private fun getRedditApiArgs(event: SlashCommandInteractionEvent): RedditApiArgs {
        val subredditArg = event.getOption(SUBREDDIT)?.asString
        val timePeriod = TimePeriod.parseTimePeriod(event.getOption(TIME_PERIOD)?.asString ?: "day").timePeriod
        val limit = event.getOption(LIMIT)?.asInt ?: 5
        logger.info { "Reddit API args - Subreddit: $subredditArg, Time Period: $timePeriod, Limit: $limit" }
        return RedditApiArgs(subredditArg, timePeriod, limit)
    }

    @Throws(IOException::class)
    private fun fetchRedditPost(
        result: RedditApiArgs,
        event: SlashCommandInteractionEvent,
        deleteDelay: Int,
        httpClient: HttpClient
    ): MessageEmbed? {
        val gson = Gson()
        val redditApiUrl =
            String.format(RedditAPIDto.redditPrefix, result.subredditArg, result.limit, result.timePeriod)
        val request = HttpGet(redditApiUrl)
        logger.info("Fetching Reddit post from URL: $redditApiUrl")
        val response = httpClient.execute(request)

        return response.let { // Using `also` to perform additional operations on the response object
            if (response.statusLine.statusCode == 200) {
                val jsonResponse = JsonParser.parseReader(InputStreamReader(response.entity.content)).asJsonObject
                EntityUtils.consume(response.entity) // Ensure the entity content is fully consumed

                val children = jsonResponse.getAsJsonObject("data").getAsJsonArray("children")

                if (children.size() == 0) {
                    logger.info("No memes found in subreddit: ${result.subredditArg}")
                    event.hook.sendMessageFormat("No memes found in the subreddit.")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay))
                    return null
                }

                val meme = children[Random.nextInt(children.size())].asJsonObject.getAsJsonObject("data")
                val redditAPIDto = gson.fromJson(meme.toString(), RedditAPIDto::class.java)

                if (redditAPIDto.isNsfw == true) {
                    logger.warn("NSFW meme detected from subreddit: ${result.subredditArg}")
                    event.hook.sendMessageFormat(
                        "I received a NSFW subreddit from %s, or reddit gave me a NSFW meme, either way somebody shoot that guy",
                        event.member
                    ).queue(invokeDeleteOnMessageResponse(deleteDelay))
                    return null
                } else if (redditAPIDto.video == true) {
                    logger.warn("Video meme detected from subreddit: ${result.subredditArg}")
                    event.hook.sendMessageFormat("I pulled back a video, whoops. Try again maybe? Or not, up to you.")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay))
                    return null
                }

                val title = meme["title"].asString
                val url = meme["url"].asString
                val author = meme["author"].asString

                logger.info("Meme fetched successfully - Title: $title, Author: $author, URL: $url")

                return EmbedBuilder().apply {
                    setTitle(title, url)
                    setAuthor(author, null, null)
                    setImage(url)
                    addField("subreddit", result.subredditArg ?: "unknown", false)
                }.build()
            } else {
                logger.error("Error response from Reddit API: ${response.statusLine.statusCode}")
                null
            }
        }
    }

    private data class RedditApiArgs(val subredditArg: String?, val timePeriod: String, val limit: Int)

    override val name: String
        get() = "meme"
    override val description: String
        get() = "This command shows a meme from the subreddit you've specified (SFW only)"
    override val optionData: List<OptionData>
        get() {
            val subreddit = OptionData(OptionType.STRING, SUBREDDIT, "Which subreddit to pull the meme from", true)
            val timePeriod = OptionData(
                OptionType.STRING,
                TIME_PERIOD,
                "What time period filter to apply to the subreddit (e.g. day/week/month/all). Default day.",
                false
            )
            TimePeriod.entries.forEach { tp -> timePeriod.addChoice(tp.timePeriod, tp.timePeriod) }
            val limit = OptionData(OptionType.INTEGER, LIMIT, "Pick from top X posts of that day. Default 5.", false)
            return listOf(subreddit, timePeriod, limit)
        }
}
