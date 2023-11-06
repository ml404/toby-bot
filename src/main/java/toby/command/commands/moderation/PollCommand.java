package toby.command.commands.moderation;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.emote.Emotes;
import toby.jpa.dto.UserDto;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;

public class PollCommand implements IModerationCommand {

    public static final String QUESTION = "question";
    public static final String CHOICES = "choices";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        InteractionHook hook = event.getHook();
        event.deferReply().queue();
        Optional<String> choiceOptional = Optional.ofNullable(event.getOption(CHOICES)).map(OptionMapping::getAsString);
        if (choiceOptional.isPresent()) {
            String question = Optional.ofNullable(event.getOption(QUESTION)).map(OptionMapping::getAsString).orElse("Poll");
            List<String> pollArgs = choiceOptional.map(s -> List.of(s.split(","))).orElse(Collections.emptyList());
            if (pollArgs.size() > 10) {
                hook.sendMessageFormat("Please keep the poll size under 10 items, or else %s.", event.getGuild().getJDA().getEmojiById(Emotes.TOBY)).queue(invokeDeleteOnMessageResponse(deleteDelay));
                return;
            }
            List<String> emojiList = List.of("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟");
            EmbedBuilder poll = new EmbedBuilder().setTitle(question).setAuthor(ctx.getAuthor().getEffectiveName()).setFooter("Please react to this poll with the emoji that aligns with the option you want to vote for");
            for (int i = 0; i < pollArgs.size(); i++) {
                poll.appendDescription(String.format("%s - **%s** \n", emojiList.get(i), pollArgs.get(i).trim()));
            }
            event.getChannel().sendMessageEmbeds(poll.build()).queue(message -> {
                for (int i = 0; i < pollArgs.size(); i++) {
                    message.addReaction(Emoji.fromUnicode(emojiList.get(i))).queue();
                }
            });
        } else {
            hook.sendMessage(getDescription()).queue(invokeDeleteOnMessageResponse(deleteDelay));
        }
    }

    @Override
    public String getName() {
        return "poll";
    }

    @Override
    public String getDescription() {
        return "Start a poll for every user in the server who has read permission in the channel you're posting to";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData question = new OptionData(OptionType.STRING, QUESTION, "Question for the poll", true);
        OptionData choices = new OptionData(OptionType.STRING, CHOICES, "Comma delimited list of answers for the poll", true);
        return List.of(question, choices);
    }
}
