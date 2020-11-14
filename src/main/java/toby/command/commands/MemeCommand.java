package toby.command.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.Gson;
import me.duncte123.botcommons.messaging.EmbedUtils;
import me.duncte123.botcommons.web.WebUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.dto.RedditAPIDto;

import java.util.List;
import java.util.Optional;
import java.util.Random;

public class MemeCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx) {
        Gson gson = new Gson();
        final TextChannel channel = ctx.getChannel();
        List<String> args = ctx.getArgs();
        if (args.size() == 0) {
            getHelp();
        } else {
            String subredditArg = args.get(0);
            String timePeriod = Optional.ofNullable(args.get(1)).orElse("day");
            String limit = Optional.ofNullable(args.get(2)).orElse("5");
            if (subredditArg.equals("sneakybackgroundfeet")) {
                channel.sendMessage("Don't talk to me.").queue();
            } else {
                WebUtils.ins.getJSONObject(String.format(RedditAPIDto.redditPrefix, subredditArg, limit, timePeriod)).async((json) -> {
                    if ((json.get("data").get("dist").asInt() == 0)) {
                        channel.sendMessage(String.format("I think you typo'd the subreddit: %s, I couldn't get anything from the reddit API", subredditArg)).queue();
                        System.out.println(json);
                        return;
                    }
                    final JsonNode parentData = json.get("data");
                    final JsonNode children = parentData.get("children");
                    Random random = new Random();
                    JsonNode meme = children.get(random.nextInt(children.size()));
                    RedditAPIDto redditAPIDto = gson.fromJson(meme.get("data").toString(), RedditAPIDto.class);
                    if (redditAPIDto.isNsfw()) {
                        channel.sendMessage(String.format("I received a NSFW subreddit from %s, or reddit gave me a NSFW meme, either way somebody shoot that guy", ctx.getAuthor())).queue();
                    } else if (redditAPIDto.getVideo()) {
                        channel.sendMessage("I pulled back a video, whoops. Try again maybe? Or not, up to you.").queue();
                    } else {
                        String title = redditAPIDto.getTitle();
                        String url = redditAPIDto.getUrl();
                        String image = redditAPIDto.getImage();
                        EmbedBuilder embed = EmbedUtils.embedImageWithTitle(title, String.format(RedditAPIDto.commentsPrefix, url), image);
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
    public String getHelp() {
        return "This command shows a meme from the subreddit you've specified (SFW only) \n" +
                "Usage: `!meme raimimemes` (picks a top 5 meme of the day by default) \n" +
                "`!meme raimimemes day/week $topXPosts`";
    }
}

