package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.helpers.MusicPlayerHelper;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

import static toby.helpers.MusicPlayerHelper.*;


public class PlayCommand implements IMusicCommand {

    private static final String VOLUME = "volume";
    private final String TYPE = "type";
    private final String START_POSITION = "start";
    private final String LINK = "link";
    private final String INTRO = "intro";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        GuildMusicManager musicManager = instance.getMusicManager(ctx.getGuild());
        String type = Optional.ofNullable(event.getOption(TYPE)).map(OptionMapping::getAsString).orElse(LINK);
        Guild guild = event.getGuild();
        int currentVolume = musicManager.getAudioPlayer().getVolume();
        instance.setPreviousVolume(currentVolume);
        Long startPosition = adjustTrackPlayingTimes(Optional.ofNullable(event.getOption(START_POSITION)).map(OptionMapping::getAsLong).orElse(0L));
        int volume = Optional.ofNullable(event.getOption(VOLUME)).map(OptionMapping::getAsInt).orElse(currentVolume);

        BlockingQueue<AudioTrack> queue = musicManager.getScheduler().getQueue();
        if (queue.isEmpty()) {
            musicManager.getAudioPlayer().setVolume(volume);
        }
        if (type.equals(INTRO)) {
            playUserIntroWithEvent(requestingUserDto, guild, event, deleteDelay, startPosition, volume);
        } else {
            String link = Optional.ofNullable(event.getOption(LINK)).map(OptionMapping::getAsString).orElse("");
            if (link.contains("youtube") && !isUrl(link)) {
                link = "ytsearch:" + link;
            }
            String finalLink = link;
            CompletableFuture<Void> loadAndPlayFuture = CompletableFuture.runAsync(() -> {
                instance.loadAndPlay(event, finalLink, true, deleteDelay, startPosition, volume);
            });

            // Wait for the CompletableFuture to complete.
            loadAndPlayFuture.join();

            // The code in this block will be executed after loadAndPlayFuture is complete.
            loadAndPlayFuture.thenRun(() -> {
                if (queue.isEmpty()) {
                    MusicPlayerHelper.nowPlaying(event, instance, volume);
                }
            });
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