package toby.command.commands.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.util.List;
import java.util.Optional;

import static toby.helpers.MusicPlayerHelper.skipTracks;


public class SkipCommand implements IMusicCommand {

    private final String SKIP = "skip";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply(true).queue();
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        int tracksToSkip = Optional.ofNullable(event.getOption(SKIP)).map(OptionMapping::getAsInt).orElse(1);
        skipTracks(event, instance, tracksToSkip, requestingUserDto.isSuperUser(), deleteDelay);
    }


    @Override
    public String getName() {
        return "skip";
    }

    @Override
    public String getDescription() {
        return "skip X number of tracks. Skips 1 by default";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.INTEGER, SKIP, "Number of tracks to skip"));
    }
}

