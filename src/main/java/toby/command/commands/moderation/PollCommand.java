package toby.command.commands.moderation;

import net.dv8tion.jda.api.EmbedBuilder;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;

public class PollCommand implements IModerationCommand {


    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        List<String> args = ctx.getArgs();
        String msg = ctx.getMessage().getContentRaw();

        if (!args.isEmpty()) {
            boolean isPresent = msg.contains("?");
            String question = isPresent ? msg.split("\\?", 2)[0].replaceAll("!poll", "").trim().concat("?") : "Poll";
            List<String> pollArgs = isPresent ? Arrays.asList(msg.split("\\?", 2)[1].split(",")) : Arrays.asList(msg.split(" ", 2)[1].split(","));
            if (pollArgs.size() > 10) {
                ctx.getChannel().sendMessageFormat("Please keep the poll size under 10 items, or else %s.", ctx.getGuild().getJDA().getEmoteById(Emotes.TOBY)).queue(message -> ICommand.deleteAfter(message, deleteDelay));
                return;
            }
            List<String> emojiList = List.of("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟");

            EmbedBuilder poll = new EmbedBuilder()
                    .setTitle(question)
                    .setAuthor(ctx.getAuthor().getName())
                    .setFooter("Please react to this poll with the emoji that aligns with the option you want to vote for");

            for (int i = 0; i < pollArgs.size(); i++) {
                poll.appendDescription(String.format("%s - **%s** \n", emojiList.get(i), pollArgs.get(i).trim()));
            }

            ctx.getChannel().sendMessage(poll.build()).queue(message -> {
                for (int i = 0; i < pollArgs.size(); i++) {
                    message.addReaction(emojiList.get(i)).queue();
                }
            });
        } else {
            getHelp(prefix);
        }
    }

    @Override
    public String getName() {
        return "poll";
    }

    @Override
    public String getHelp(String prefix) {
        return "Start a poll for every user in the server who has read permission in the channel you're posting to\n" +
                String.format("`%spoll question title? (this is optional, don't have to have a question title) and then each option separated by a comma(,)`\n", prefix) +
                String.format("e.g. `%spoll question title? option1,option2`", prefix);
    }
}
