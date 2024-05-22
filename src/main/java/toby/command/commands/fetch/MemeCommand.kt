package toby.command.commands.fetch

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.dto.web.RedditAPIDto
import toby.dto.web.RedditAPIDto.TimePeriod
import toby.jpa.dto.UserDto
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

class MemeCommand : IFetchCommand {
    private val SUBREDDIT = "subreddit"
    private val TIME_PERIOD = "timeperiod"
    private val LIMIT = "limit"
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        try {
            handle(ctx, HttpClients.createDefault(), requestingUserDto, deleteDelay)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    @Throws(IOException::class)
    fun handle(ctx: CommandContext?, httpClient: HttpClient, requestingUserDto: UserDto?, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        if (!requestingUserDto!!.memePermission) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }
        val result = getRedditApiArgs(event)
        if (result.subredditArgOptional.isPresent) {
            val subredditArg = result.subredditArgOptional.get()
            if (subredditArg == "sneakybackgroundfeet") {
                event.hook.sendMessageFormat("Don't talk to me.").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            } else {
                val embed = fetchRedditPost(result, event, deleteDelay!!, httpClient)
                event.hook.sendMessageEmbeds(embed!!).queue()
            }
        }
    }

    private fun getRedditApiArgs(event: SlashCommandInteractionEvent): RedditApiArgs {
        val subredditArgOptional = Optional.ofNullable(event.getOption(SUBREDDIT)).map { obj: OptionMapping -> obj.asString }
        val timePeriodOptional = Optional.ofNullable(event.getOption(TIME_PERIOD)).map { obj: OptionMapping -> obj.asString }
        val timePeriod = TimePeriod.parseTimePeriod(timePeriodOptional.orElse("day")).timePeriod
        val limit = Optional.ofNullable(event.getOption(LIMIT)).map { obj: OptionMapping -> obj.asInt }.orElse(5)
        return RedditApiArgs(subredditArgOptional, timePeriod, limit)
    }

    @Throws(IOException::class)
    private fun fetchRedditPost(result: RedditApiArgs, event: SlashCommandInteractionEvent, deleteDelay: Int, httpClient: HttpClient): MessageEmbed? {
        val gson = Gson()
        val redditApiUrl = String.format(RedditAPIDto.redditPrefix, result.subredditArgOptional.get(), result.limit, result.timePeriod)
        val request = HttpGet(redditApiUrl)
        val response = httpClient.execute(request)

        // Check the response code (200 indicates success)
        if (response.statusLine.statusCode == 200) {
            // Parse the JSON response
            val jsonResponse = JsonParser.parseReader(InputStreamReader(response.entity.content)).getAsJsonObject()
            val children = jsonResponse.getAsJsonObject("data").getAsJsonArray("children")
            val random = Random()

            // Extract the data you need from the JSON
            val meme = children[random.nextInt(children.size())]
                    .getAsJsonObject()
                    .getAsJsonObject("data")
            val redditAPIDto = gson.fromJson(meme.toString(), RedditAPIDto::class.java)
            if (redditAPIDto.isNsfw!!) {
                event.hook.sendMessageFormat("I received a NSFW subreddit from %s, or reddit gave me a NSFW meme, either way somebody shoot that guy", event.member).queue(invokeDeleteOnMessageResponse(deleteDelay))
            } else if (redditAPIDto.video!!) {
                event.hook.sendMessageFormat("I pulled back a video, whoops. Try again maybe? Or not, up to you.").queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
            val title = meme["title"].asString
            val url = meme["url"].asString
            val author = meme["author"].asString

            // Create a JDA EmbedBuilder and return the MessageEmbed
            val embedBuilder = EmbedBuilder()
            embedBuilder.setTitle(title, url)
            embedBuilder.setAuthor(author, null, null)
            embedBuilder.setImage(url)
            embedBuilder.addField("subreddit", result.subredditArgOptional.get(), false)
            return embedBuilder.build()
        }
        return null // Return null if there was an error
    }

    @JvmRecord
    private data class RedditApiArgs(val subredditArgOptional: Optional<String>, val timePeriod: String, val limit: Int)

    override val name: String
        get() = "meme"
    override val description: String
        get() = "This command shows a meme from the subreddit you've specified (SFW only)"
    override val optionData: List<OptionData>
        get() {
            val subreddit = OptionData(OptionType.STRING, SUBREDDIT, "Which subreddit to pull the meme from", true)
            val timePeriod = OptionData(OptionType.STRING, TIME_PERIOD, "What time period filter to apply to the subreddit (e.g. day/week/month/all). Default day.", false)
            Arrays.stream(TimePeriod.entries.toTypedArray()).forEach { tp: TimePeriod -> timePeriod.addChoice(tp.timePeriod, tp.timePeriod) }
            val limit = OptionData(OptionType.INTEGER, LIMIT, "Pick from top X posts of that day. Default 5.", false)
            return listOf(subreddit, timePeriod, limit)
        }
}
