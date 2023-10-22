package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.emote.Emotes;
import toby.jpa.dto.BrotherDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IBrotherService;

import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.getConsumer;


public class BrotherCommand implements IMiscCommand {

    private final IBrotherService brotherService;
    public static Long tobyId = 320919876883447808L;
    private final String BROTHER = "brother";

    public BrotherCommand(IBrotherService brotherService) {
        this.brotherService = brotherService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        Guild guild = event.getGuild();
        Emoji tobyEmote = guild.getJDA().getEmojiById(Emotes.TOBY);

        determineBrother(event, tobyEmote, deleteDelay);
    }

    private void determineBrother(SlashCommandInteractionEvent event, Emoji tobyEmote, int deleteDelay) {
        InteractionHook hook = event.getHook();
        if (tobyId.equals(event.getUser().getIdLong())) {
            hook.sendMessageFormat("You're not my fucking brother Toby, you're me %s", tobyEmote).queue(getConsumer(deleteDelay));
            return;
        }
        Optional<Mentions> optionalMentions = Optional.ofNullable(event.getOption(BROTHER)).map(OptionMapping::getMentions);
        if (optionalMentions.isEmpty()) {
            Optional<BrotherDto> brother = brotherService.getBrotherById(event.getUser().getIdLong());
            brother.ifPresentOrElse(
                    brotherDto -> hook.sendMessageFormat("Of course you're my brother %s.", brotherDto.getBrotherName()).queue(getConsumer(deleteDelay)),
                    () -> hook.sendMessageFormat("You're not my fucking brother %s ffs %s", event.getUser().getName(), tobyEmote).queue(getConsumer(deleteDelay))
            );
            return;
        }
        List<Member> mentions = optionalMentions.get().getMembers();
        mentions.forEach(member -> {
            Optional<BrotherDto> brother = brotherService.getBrotherById(member.getIdLong());
            brother.ifPresentOrElse(
                    brotherDto -> hook.sendMessageFormat("Of course you're my brother %s.", brotherDto.getBrotherName()).queue(getConsumer(deleteDelay)),
                    () -> hook.sendMessageFormat("You're not my fucking brother %s ffs %s", event.getUser().getName(), tobyEmote).queue(getConsumer(deleteDelay))
            );
        });
    }

    @Override
    public String getName() {
        return BROTHER;
    }

    @Override
    public String getDescription() {
        return "Let me tell you if you're my brother.";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.USER, BROTHER, "Tag the person who you want to check the brother status of."));
    }
}