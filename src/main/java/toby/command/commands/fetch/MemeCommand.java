package toby.command.commands.fetch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.dto.web.RedditAPIDto;
import toby.jpa.dto.UserDto;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;

public class MemeCommand implements IFetchCommand {

    private final String SUBREDDIT = "subreddit";
    private final String TIME_PERIOD = "timeperiod";
    private final String LIMIT = "limit";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        try {
            handle(ctx, HttpClients.createDefault(), requestingUserDto, deleteDelay);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void handle(CommandContext ctx, HttpClient httpClient, UserDto requestingUserDto, Integer deleteDelay) throws IOException {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        deleteAfter(event.getHook(), deleteDelay);
        if (!requestingUserDto.hasMemePermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }
        RedditApiArgs result = getRedditApiArgs(event);
        if (result.subredditArgOptional().isPresent()) {
            String subredditArg = result.subredditArgOptional().get();
            if (subredditArg.equals("sneakybackgroundfeet")) {
                event.getChannel().sendMessageFormat("Don't talk to me.").queue(getConsumer(deleteDelay));
            } else {
                MessageEmbed embed = fetchRedditPost(result, event, deleteDelay, httpClient);
                event.getChannel().sendMessageEmbeds(embed).queue();
            }
        }
    }

    @NotNull
    private RedditApiArgs getRedditApiArgs(SlashCommandInteractionEvent event) {
        Optional<String> subredditArgOptional = Optional.ofNullable(event.getOption(SUBREDDIT)).map(OptionMapping::getAsString);
        Optional<String> timePeriodOptional = Optional.ofNullable(event.getOption(TIME_PERIOD)).map(OptionMapping::getAsString);
        String timePeriod = RedditAPIDto.TimePeriod.valueOf(timePeriodOptional.orElse("DAY")).toString().toLowerCase();
        int limit = Optional.ofNullable(event.getOption(LIMIT)).map(OptionMapping::getAsInt).orElse(5);

        return new RedditApiArgs(subredditArgOptional, timePeriod, limit);
    }

    private MessageEmbed fetchRedditPost(RedditApiArgs result, SlashCommandInteractionEvent event, int deleteDelay, HttpClient httpClient) throws IOException {
        Gson gson = new Gson();
        String redditApiUrl = String.format(RedditAPIDto.redditPrefix, result.subredditArgOptional.get(), result.limit(), result.timePeriod());
        HttpGet request = new HttpGet(redditApiUrl);
        HttpResponse response = httpClient.execute(request);

        // Check the response code (200 indicates success)
        if (response.getStatusLine().getStatusCode() == 200) {
            // Parse the JSON response
            JsonObject jsonResponse = JsonParser.parseReader(new InputStreamReader(response.getEntity().getContent())).getAsJsonObject();

            JsonArray children = jsonResponse.getAsJsonObject("data").getAsJsonArray("children");
            Random random = new Random();

            // Extract the data you need from the JSON
            JsonObject meme = children
                    .get(random.nextInt(children.size()))
                    .getAsJsonObject()
                    .getAsJsonObject("data");
            RedditAPIDto redditAPIDto = gson.fromJson(meme.toString(), RedditAPIDto.class);
            if (redditAPIDto.isNsfw()) {
                event.getHook().sendMessageFormat("I received a NSFW subreddit from %s, or reddit gave me a NSFW meme, either way somebody shoot that guy", event.getMember()).queue(getConsumer(deleteDelay));
            } else if (redditAPIDto.getVideo()) {
                event.getHook().sendMessageFormat("I pulled back a video, whoops. Try again maybe? Or not, up to you.").queue(getConsumer(deleteDelay));
            }

            String title = meme.get("title").getAsString();
            String url = meme.get("url").getAsString();
            String author = meme.get("author").getAsString();

            // Create a JDA EmbedBuilder and return the MessageEmbed
            EmbedBuilder embedBuilder = new EmbedBuilder();
            embedBuilder.setTitle(title, url);
            embedBuilder.setAuthor(author, null, null);
            embedBuilder.setImage(url);
            embedBuilder.addField("subreddit", result.subredditArgOptional.get(), false);

            return embedBuilder.build();
        }

        return null; // Return null if there was an error

    }

    private record RedditApiArgs(Optional<String> subredditArgOptional, String timePeriod, int limit) {
    }


    @Override
    public String getName() {
        return "meme";
    }

    @Override
    public String getDescription() {
        return "This command shows a meme from the subreddit you've specified (SFW only)";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData subreddit = new OptionData(OptionType.STRING, SUBREDDIT, "Which subreddit to pull the meme from", true);
        OptionData timePeriod = new OptionData(OptionType.STRING, TIME_PERIOD, "What time period filter to apply to the subreddit (e.g. day/week/month/all). Default day.", false);
        Arrays.stream(RedditAPIDto.TimePeriod.values()).forEach(tp -> timePeriod.addChoice(tp.getTimePeriod(), tp.name()));
        OptionData limit = new OptionData(OptionType.INTEGER, LIMIT, "Pick from top X posts of that day. Default 5.", false);

        return List.of(subreddit, timePeriod, limit);
    }
}

