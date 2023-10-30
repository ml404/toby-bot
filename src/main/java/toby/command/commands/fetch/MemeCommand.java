package toby.command.commands.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.Gson;
import me.duncte123.botcommons.messaging.EmbedUtils;
import me.duncte123.botcommons.web.WebUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.dto.web.RedditAPIDto;
import toby.jpa.dto.UserDto;

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
        handle(ctx, WebUtils.ins, requestingUserDto, deleteDelay);
    }

    public void handle(CommandContext ctx, WebUtils webUtils, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        deleteAfter(event.getHook(), deleteDelay);
        Gson gson = new Gson();
        User member = ctx.getAuthor();
        event.deferReply().queue();

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
                webUtils.getJSONObject(String.format(RedditAPIDto.redditPrefix, subredditArg, result.limit(), result.timePeriod())).async((json) -> {
                    if ((json.get("data").get("dist").asInt() == 0)) {
                        event.getChannel().sendMessageFormat("I think you typo'd the subreddit: '%s', I couldn't get anything from the reddit API", subredditArg).queue(getConsumer(deleteDelay));
                        return;
                    }
                    final JsonNode parentData = json.get("data");
                    final JsonNode children = parentData.get("children");
                    Random random = new Random();
                    JsonNode meme = children.get(random.nextInt(children.size()));
                    RedditAPIDto redditAPIDto = gson.fromJson(meme.get("data").toString(), RedditAPIDto.class);
                    if (redditAPIDto.isNsfw()) {
                        event.getHook().sendMessageFormat("I received a NSFW subreddit from %s, or reddit gave me a NSFW meme, either way somebody shoot that guy", member).queue(getConsumer(deleteDelay));
                    } else if (redditAPIDto.getVideo()) {
                        event.getHook().sendMessageFormat("I pulled back a video, whoops. Try again maybe? Or not, up to you.").queue(getConsumer(deleteDelay));
                    } else {
                        String title = redditAPIDto.getTitle();
                        String url = redditAPIDto.getUrl();
                        String image = redditAPIDto.getImage();
                        EmbedBuilder embed = EmbedUtils.embedImageWithTitle(title, String.format(RedditAPIDto.commentsPrefix, url), image);
                        embed.addField("Subreddit", subredditArg, false);
                        event.getHook().sendMessageEmbeds(embed.build()).queue();

                    }
                });
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

