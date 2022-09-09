package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.emote.Emotes;
import toby.jpa.dto.BrotherDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IBrotherService;

import java.util.List;
import java.util.Optional;


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
        Optional<Mentions> optionalMentions = Optional.ofNullable(event.getOption(BROTHER).getMentions());
        if (optionalMentions.isEmpty()) {
            BrotherDto brother = brotherService.getBrotherById(event.getUser().getIdLong());
            if (brother!=null) {
                event.getHook().sendMessageFormat("Of course you're my brother %s.", brother.getBrotherName()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            } else if (tobyId.equals(event.getUser().getIdLong())) {
                event.getHook().sendMessageFormat("You're not my fucking brother Toby, you're me %s", tobyEmote).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            } else
                event.getHook().sendMessageFormat("You're not my fucking brother %s ffs %s", event.getUser().getName(), tobyEmote).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        }
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