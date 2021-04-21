package toby.command.commands.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.Gson;
import me.duncte123.botcommons.messaging.EmbedUtils;
import me.duncte123.botcommons.web.WebUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.User;
import toby.command.CommandContext;
import toby.dto.web.RedditAPIDto;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Random;

public class MemeCommand implements IFetchCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {

        Gson gson = new Gson();
        final TextChannel channel = ctx.getChannel();
        List<String> args = ctx.getArgs();
        User member = ctx.getAuthor();

        if(!requestingUserDto.hasMemePermission()){
            channel.sendMessageFormat(
                    "You do not have adequate permissions to use this command, talk to the server owner: %s",
                    ctx.getGuild().getOwner().getNickname()
            ).queue();
            return ;
        }

        if (args.size() == 0) {
            channel.sendMessage(getHelp(prefix)).queue();
        } else {
            String subredditArg = args.get(0);
            String timePeriod;
            int limit;
            try {
                timePeriod = RedditAPIDto.TimePeriod.valueOf(args.get(1).toUpperCase()).toString().toLowerCase();
            } catch (IndexOutOfBoundsException e) {
                timePeriod = "day";
            } catch (IllegalArgumentException e) {
                timePeriod = "day";
                channel.sendMessage(String.format("You entered a time period not supported: **%s**\\. Please use one of: day/week/month/all \n", args.get(1)) +
                        String.format("Using default time period of %s", timePeriod)).queue();
            }
            try {
                limit = Integer.parseInt(args.get(2));
            } catch (IndexOutOfBoundsException e) {
                limit = 5;
            }
            catch (NumberFormatException e){
                limit = 5;
                channel.sendMessage(String.format("Invalid number supplied, using default value %d", limit)).queue();
            }
            if (subredditArg.equals("sneakybackgroundfeet")) {
                channel.sendMessage("Don't talk to me.").queue();
            } else {
                WebUtils.ins.getJSONObject(String.format(RedditAPIDto.redditPrefix, subredditArg, limit, timePeriod)).async((json) -> {
                    if ((json.get("data").get("dist").asInt() == 0)) {
                        channel.sendMessage(String.format("I think you typo'd the subreddit: '%s', I couldn't get anything from the reddit API", subredditArg)).queue();
                        return;
                    }
                    final JsonNode parentData = json.get("data");
                    final JsonNode children = parentData.get("children");
                    Random random = new Random();
                    JsonNode meme = children.get(random.nextInt(children.size()));
                    RedditAPIDto redditAPIDto = gson.fromJson(meme.get("data").toString(), RedditAPIDto.class);
                    if (redditAPIDto.isNsfw()) {
                        channel.sendMessage(String.format("I received a NSFW subreddit from %s, or reddit gave me a NSFW meme, either way somebody shoot that guy", member)).queue();
                    } else if (redditAPIDto.getVideo()) {
                        channel.sendMessage("I pulled back a video, whoops. Try again maybe? Or not, up to you.").queue();
                    } else {
                        String title = redditAPIDto.getTitle();
                        String url = redditAPIDto.getUrl();
                        String image = redditAPIDto.getImage();
                        EmbedBuilder embed = EmbedUtils.embedImageWithTitle(title, String.format(RedditAPIDto.commentsPrefix, url), image);
                        embed.setAuthor(redditAPIDto.getAuthor());
                        channel.sendMessage(embed.build()).queue();

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
    public String getHelp(String prefix) {
        return "This command shows a meme from the subreddit you've specified (SFW only) \n" +
                String.format("Usage: `%smeme raimimemes` (picks a top 5 meme of the day by default) \n", prefix) +
                String.format("`%smeme raimimemes day/week/month/all $topXPosts`", prefix);
    }
}

