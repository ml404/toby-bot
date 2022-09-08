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
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.dto.web.RedditAPIDto;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class MemeCommand implements IFetchCommand {

    private final String SUBREDDIT = "subreddit";
    private final String TIME_PERIOD = "timeperiod";
    private final String LIMIT = "limit";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        ICommand.deleteAfter(event.getHook(), deleteDelay);

        Gson gson = new Gson();
        List<OptionMapping> args = event.getOptions();
        User member = ctx.getAuthor();
        event.deferReply().queue();

        if(!requestingUserDto.hasMemePermission()){
            event.replyFormat(
                    "You do not have adequate permissions to use this command, talk to the server owner: %s",
                    event.getGuild().getOwner().getNickname())
            .queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return ;
        }

        if (args.size() == 0) {
            event.reply(getDescription()).queue();
        } else {
            String subredditArg = event.getOption(SUBREDDIT).getAsString();
            String timePeriod;
            int limit;
            try {
                timePeriod = RedditAPIDto.TimePeriod.valueOf(event.getOption(TIME_PERIOD).getAsString()).toString().toLowerCase();
            } catch (IndexOutOfBoundsException e) {
                timePeriod = "day";
            } catch (IllegalArgumentException e) {
                timePeriod = "day";
                event.replyFormat("You entered a time period not supported: **%s**\\. Please use one of: %s \n", args.get(1), Arrays.stream(RedditAPIDto.TimePeriod.values()).map(Enum::name).map(String::toLowerCase).collect(Collectors.joining("/")) +
                        String.format("Using default time period of %s", timePeriod)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
            try {
                limit = event.getOption(LIMIT).getAsInt();
            } catch (Error e) {
                limit = 5;
            }
            catch (NumberFormatException e){
                limit = 5;
                event.replyFormat("Invalid number supplied, using default value %d", limit).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
            if (subredditArg.equals("sneakybackgroundfeet")) {
                event.reply("Don't talk to me.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            } else {
                WebUtils.ins.getJSONObject(String.format(RedditAPIDto.redditPrefix, subredditArg, limit, timePeriod)).async((json) -> {
                    if ((json.get("data").get("dist").asInt() == 0)) {
                        event.replyFormat("I think you typo'd the subreddit: '%s', I couldn't get anything from the reddit API", subredditArg).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                        return;
                    }
                    final JsonNode parentData = json.get("data");
                    final JsonNode children = parentData.get("children");
                    Random random = new Random();
                    JsonNode meme = children.get(random.nextInt(children.size()));
                    RedditAPIDto redditAPIDto = gson.fromJson(meme.get("data").toString(), RedditAPIDto.class);
                    if (redditAPIDto.isNsfw()) {
                        event.replyFormat("I received a NSFW subreddit from %s, or reddit gave me a NSFW meme, either way somebody shoot that guy", member).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    } else if (redditAPIDto.getVideo()) {
                        event.reply("I pulled back a video, whoops. Try again maybe? Or not, up to you.").queue(message -> ICommand.deleteAfter(message, deleteDelay));
                    } else {
                        String title = redditAPIDto.getTitle();
                        String url = redditAPIDto.getUrl();
                        String image = redditAPIDto.getImage();
                        EmbedBuilder embed = EmbedUtils.embedImageWithTitle(title, String.format(RedditAPIDto.commentsPrefix, url), image);
                        embed.addField("Subreddit", subredditArg, false);
                        event.replyEmbeds(embed.build()).queue();

                    }
                });
            }
        }
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
        Arrays.stream(RedditAPIDto.TimePeriod.values()).forEach(tp -> timePeriod.addChoice(tp.getTimePeriod(), tp.getTimePeriod()));
        OptionData limit = new OptionData(OptionType.INTEGER, LIMIT, "Pick from top X posts of that day. Default 5.", false);

        return List.of(subreddit,timePeriod,limit);
    }
}

