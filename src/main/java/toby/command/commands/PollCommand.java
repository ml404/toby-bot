package toby.command.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import toby.BotConfig;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PollCommand implements ICommand {


    @Override
    public void handle(CommandContext ctx) {
        List<String> args = ctx.getArgs();
        String msg = ctx.getMessage().getContentRaw();

        if (!args.isEmpty()) {
            List<String> pollArgs = Arrays.asList(msg.split(" ", 2)[1].split(","));
            if (pollArgs.size() > 10) {
                ctx.getChannel().sendMessageFormat("Please keep the poll size under 10 items, or else %s.", ctx.getGuild().getJDA().getEmoteById(Emotes.TOBY)).queue();
                return;
            }
            List<String> emojiList = List.of("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟");

            EmbedBuilder poll = new EmbedBuilder()
                    .setTitle("Poll")
                    .setFooter("Please react to this poll with the emoji that aligns with the option you want to vote for");

            for (int i = 0; i < pollArgs.size(); i++) {
                poll.appendDescription(String.format("`%s : %s` \n", emojiList.get(i), pollArgs.get(i).trim()));
            }

            ctx.getChannel().sendMessage(poll.build()).queue(message -> {
                for (int i = 0; i < pollArgs.size(); i++) {
                    message.addReaction(emojiList.get(i)).queue();
                }
            });
        } else {
            getHelp();
        }
    }

    @Override
    public String getName() {
        return "poll";
    }

    @Override
    public String getHelp() {
        return "Start a poll for every user in the server who has read permission in the channel you're posting to /n" +
                String.format("`%s%s each option separated by a comma(,)` /n", BotConfig.configMap.get("PREFIX"), getName())+
                "e.g. !poll option1,option2";
    }
}
