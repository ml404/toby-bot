package toby.command.commands.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.helpers.URLHelper;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;
import static toby.helpers.MusicPlayerHelper.adjustTrackPlayingTimes;


public class NowDigOnThisCommand implements IMusicCommand {

    private final String LINK = "link";
    private final String START_POSITION = "start";
    private final String VOLUME = "volume";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (requestingUserDto.hasDigPermission()) {
            Optional<String> linkOptional = Optional.ofNullable(event.getOption(LINK)).map(OptionMapping::getAsString);
            if (linkOptional.isEmpty()) {
                event.getHook().sendMessageFormat("Correct usage is `%snowdigonthis <youtube linkOptional>`", "/").queue(getConsumer(deleteDelay));
                return;
            }
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
            String link = linkOptional.get();
            if (link.contains("youtube") && !URLHelper.isValidURL(link)) link = "ytsearch:" + linkOptional;
            Long startPosition = adjustTrackPlayingTimes(Optional.ofNullable(event.getOption(START_POSITION)).map(OptionMapping::getAsLong).orElse(0L));
            GuildMusicManager musicManager = instance.getMusicManager(ctx.getGuild());
            int volume = Optional.ofNullable(event.getOption(VOLUME)).map(OptionMapping::getAsInt).orElse(musicManager.getAudioPlayer().getVolume());
            if (musicManager.getScheduler().getQueue().isEmpty()) {
                musicManager.getAudioPlayer().setVolume(volume);
            }
            instance.loadAndPlay(event, link, false, deleteDelay, startPosition, volume);
        } else
            sendErrorMessage(event, deleteDelay);
    }


    @Override
    public String getName() {
        return "nowdigonthis";
    }

    @Override
    public String getDescription() {
        return "Plays a song which cannot be skipped";
    }

    @Override
    public String getErrorMessage(String name) {
        return String.format("I'm gonna put some dirt in your eye %s", name);
    }

    @Override
    public void sendErrorMessage(SlashCommandInteractionEvent event, Integer deleteDelay) {
        event.getHook().sendMessage(getErrorMessage(event.getMember().getNickname())).queue(getConsumer(deleteDelay));
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData linkArg = new OptionData(OptionType.STRING, LINK, "Link to play that cannot be stopped unless requested by a super user", true);
        OptionData startPositionArg = new OptionData(OptionType.NUMBER, START_POSITION, "Start position of the track in seconds");
        OptionData volume = new OptionData(OptionType.INTEGER, VOLUME, "Volume to play at");

        return List.of(linkArg, volume, startPositionArg);
    }
}