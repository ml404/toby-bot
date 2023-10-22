package toby.command.commands.music;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;
import static toby.helpers.MusicPlayerHelper.*;


public class PlayCommand implements IMusicCommand {

    private static final String VOLUME = "volume";
    private final String TYPE = "type";
    private final String START_POSITION = "start";
    private final String LINK = "link";
    private final String INTRO = "intro";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        String type = Optional.ofNullable(event.getOption(TYPE)).map(OptionMapping::getAsString).orElse(LINK);

        if (type.isEmpty()) {
            event.getHook().sendMessage("Correct usage is `!play <youtube link>`").queue(getConsumer(deleteDelay));
            return;
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        PlayerManager instance = PlayerManager.getInstance();
        Guild guild = event.getGuild();
        GuildMusicManager musicManager = instance.getMusicManager(guild);
        int currentVolume = musicManager.getAudioPlayer().getVolume();
        instance.setPreviousVolume(currentVolume);
        Long startPosition = adjustTrackPlayingTimes(Optional.ofNullable(event.getOption(START_POSITION)).map(OptionMapping::getAsLong).orElse(0L));
        int volume = Optional.ofNullable(event.getOption(VOLUME)).map(OptionMapping::getAsInt).orElse(currentVolume);

        if (musicManager.getScheduler().getQueue().isEmpty()) {
            musicManager.getAudioPlayer().setVolume(volume);
        }
        if (type.equals(INTRO)) {
            playUserIntroWithEvent(requestingUserDto, guild, event, deleteDelay, startPosition, volume);
        } else {
            String link = Optional.ofNullable(event.getOption(LINK)).map(OptionMapping::getAsString).orElse("");
            if (link.contains("youtube") && !isUrl(link)) {
                link = "ytsearch:" + link;
            }
            instance.loadAndPlay(event, link, true, deleteDelay, startPosition, volume);
        }
    }


    @Override
    public String getName() {
        return "play";
    }

    @Override
    public String getDescription() {
        return "Plays a song. You may optionally specify a start time";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData type = new OptionData(OptionType.STRING, TYPE, "Type of thing you're playing (link or intro). Defaults to link");
        type.addChoice(LINK, LINK);
        type.addChoice(INTRO, INTRO);
        OptionData link = new OptionData(OptionType.STRING, LINK, "link you would like to play");
        OptionData startPosition = new OptionData(OptionType.NUMBER, START_POSITION, "Start position of the track in seconds (defaults to 0)");
        OptionData volume = new OptionData(OptionType.INTEGER, VOLUME, "Volume to play at");
        return List.of(link, type, startPosition, volume);
    }
}