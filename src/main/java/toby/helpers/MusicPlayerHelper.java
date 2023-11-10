package toby.helpers;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.web.ErrorResponseException;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static toby.command.ICommand.invokeDeleteOnMessageResponse;
import static toby.command.commands.music.IMusicCommand.sendDeniedStoppableMessage;

public class MusicPlayerHelper {

    private static final String webUrl = "https://gibe-toby-bot.herokuapp.com/";

    public static final int SECOND_MULTIPLIER = 1000;
    private static final Map<Long, Map<Channel, Message>> guildLastNowPlayingMessage = new HashMap<>();

    public static void playUserIntro(UserDto dbUser, Guild guild, int deleteDelay, Long startPosition, int volume) {
        playUserIntro(dbUser, guild, null, deleteDelay, startPosition, volume);
    }

    public static void playUserIntro(UserDto dbUser, Guild guild, SlashCommandInteractionEvent event, int deleteDelay, Long startPosition, int volume) {
        MusicDto musicDto = dbUser.getMusicDto();
        PlayerManager instance = PlayerManager.getInstance();
        int currentVolume = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().getVolume();
        if (musicDto != null && musicDto.getFileName() != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlay(guild,
                    event,
                    String.format(webUrl + "/music?id=%s", musicDto.getId()),
                    true,
                    0,
                    startPosition,
                    introVolume != null ? introVolume : volume);
        } else if (musicDto != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlay(guild, event, Arrays.toString(dbUser.getMusicDto().getMusicBlob()), true, deleteDelay, startPosition, introVolume);
        }
    }

    public static void nowPlaying(IReplyCallback event, PlayerManager playerManager, Integer deleteDelay) {
        GuildMusicManager musicManager = playerManager.getMusicManager(event.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();
        AudioTrack track = audioPlayer.getPlayingTrack();
        InteractionHook hook = event.getHook();
        if (checkForPlayingTrack(track, hook, deleteDelay)) return;
        checkTrackAndSendMessage(track, hook, audioPlayer.getVolume());
    }

    private static void checkTrackAndSendMessage(AudioTrack track, InteractionHook hook, int volume) {
        String nowPlaying = getNowPlayingString(track, volume);
        Button pausePlay = Button.primary("pause/play", "⏯");
        Button stop = Button.primary("stop", "⏹");
        Interaction interaction = hook.getInteraction();
        long guildId = interaction.getGuild().getIdLong();
        Channel channel = interaction.getChannel();

        // Get the previous "Now Playing" messages if they exist
        Map<Channel, Message> channelMessageMap = guildLastNowPlayingMessage.get(guildId);
        try {
            if (channelMessageMap == null || channelMessageMap.get(channel) == null) {
                sendNewNowPlayingMessage(hook, channel, nowPlaying, pausePlay, stop, guildId);
            } else {
                // Update the existing "Now Playing" messages
                String updatedNowPlaying = getNowPlayingString(track, volume);
                for (Message message : channelMessageMap.values()) {
                    try {
                        message.editMessage(updatedNowPlaying).setActionRow(pausePlay, stop).queue();
                    } catch (ErrorResponseException e) {
                        // Log exception or handle accordingly
                    }

                }
                hook.deleteOriginal().queue();
            }
        } catch (IllegalArgumentException | ErrorResponseException e) {
            // Send a new "Now Playing" message and store it
            sendNewNowPlayingMessage(hook, channel, nowPlaying, pausePlay, stop, guildId);
        }
    }

    private static void sendNewNowPlayingMessage(InteractionHook hook, Channel channel, String nowPlaying, Button pausePlay, Button stop, long guildId) {
        // Send a new "Now Playing" message and store it
        Message nowPlayingMessage = hook.sendMessage(nowPlaying).setActionRow(pausePlay, stop).complete();
        // Store message in the guild's map
        Map<Channel, Message> channelMessageMap = guildLastNowPlayingMessage.get(guildId);
        if (channelMessageMap == null) {
            channelMessageMap = new HashMap<>();
        }
        channelMessageMap.put(channel, nowPlayingMessage);
        guildLastNowPlayingMessage.put(guildId, channelMessageMap);
    }

    static boolean checkForPlayingTrack(AudioTrack track, InteractionHook hook, Integer deleteDelay) {
        if (track == null) {
            hook.sendMessage("There is no track playing currently").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return true;
        }
        return false;
    }

    public static void stopSong(IReplyCallback event, GuildMusicManager musicManager, boolean canOverrideSkips, Integer deleteDelay) {
        InteractionHook hook = event.getHook();
        if (PlayerManager.getInstance().isCurrentlyStoppable() || canOverrideSkips) {
            TrackScheduler scheduler = musicManager.getScheduler();
            scheduler.stopTrack(true);
            scheduler.getQueue().clear();
            scheduler.setLooping(false);
            musicManager.getAudioPlayer().setPaused(false);
            hook.deleteOriginal().queue();
            hook.sendMessage("The player has been stopped and the queue has been cleared").queue(invokeDeleteOnMessageResponse(deleteDelay));
            resetNowPlayingMessage(event.getGuild().getIdLong());
        } else {
            sendDeniedStoppableMessage(hook, musicManager, deleteDelay);
        }
    }

    public static String getNowPlayingString(AudioTrack playingTrack, int volume) {
        AudioTrackInfo info = playingTrack.getInfo();
        if (!info.isStream) {
            long position = playingTrack.getPosition();
            long duration = playingTrack.getDuration();
            String songPosition = formatTime(position);
            String songDuration = formatTime(duration);
            return String.format("Now playing `%s` by `%s` `[%s/%s]` (Link: <%s>) with volume '%d'", info.title, info.author, songPosition, songDuration, info.uri, volume);

        } else {
            return String.format("Now playing `%s` by `%s` (Link: <%s>) with volume '%d'", info.title, info.author, info.uri, volume);
        }
    }

    public static void changePauseStatusOnTrack(IReplyCallback event, GuildMusicManager musicManager, Integer
            deleteDelay) {
        AudioPlayer audioPlayer = musicManager.getAudioPlayer();
        boolean paused = audioPlayer.isPaused();
        String message = paused ? "Resuming: `" : "Pausing: `";
        sendMessageAndSetPaused(audioPlayer, event.getHook(), message, deleteDelay, !paused);

    }

    private static void sendMessageAndSetPaused(AudioPlayer audioPlayer, InteractionHook hook, String content, Integer deleteDelay, boolean paused) {
        AudioTrack track = audioPlayer.getPlayingTrack();
        hook.sendMessage(content)
                .addContent(track.getInfo().title)
                .addContent("` by `")
                .addContent(track.getInfo().author)
                .addContent("`")
                .queue(invokeDeleteOnMessageResponse(deleteDelay));
        audioPlayer.setPaused(paused);
    }

    public static void skipTracks(IReplyCallback event, PlayerManager playerManager, int tracksToSkip, boolean canOverrideSkips, Integer deleteDelay) {
        InteractionHook hook = event.getHook();
        GuildMusicManager musicManager = playerManager.getMusicManager(event.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();

        if (audioPlayer.getPlayingTrack() == null) {
            hook.sendMessage("There is no track playing currently").queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }
        if (tracksToSkip < 0) {
            hook.sendMessage("You're not too bright, but thanks for trying").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }

        if (playerManager.isCurrentlyStoppable() || canOverrideSkips) {
            for (int i = 0; i < tracksToSkip; i++) {
                musicManager.getScheduler().nextTrack();
            }
            musicManager.getScheduler().setLooping(false);
            hook.sendMessageFormat("Skipped %d track(s)", tracksToSkip).queue(invokeDeleteOnMessageResponse(deleteDelay));
        } else sendDeniedStoppableMessage(hook, musicManager, deleteDelay);
    }

    public static String formatTime(long timeInMillis) {
        final long hours = timeInMillis / TimeUnit.HOURS.toMillis(1);
        final long minutes = timeInMillis / TimeUnit.MINUTES.toMillis(1);
        final long seconds = timeInMillis % TimeUnit.MINUTES.toMillis(1) / TimeUnit.SECONDS.toMillis(1);

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static Long adjustTrackPlayingTimes(Long startTime) {
        Map<String, Long> adjustmentMap = new HashMap<>();

        if (startTime > 0L) adjustmentMap.put(MusicDto.Adjustment.START.name(), startTime);

        if (adjustmentMap.isEmpty()) {
            return 0L;
        }

        if (adjustmentMap.containsKey(MusicDto.Adjustment.START.name())) {
            return adjustmentMap.get(MusicDto.Adjustment.START.name()) * SECOND_MULTIPLIER;
        }
//       TODO: return a map when end can be specified too

//        if (adjustmentMap.containsKey(MusicDto.Adjustment.END.name())){
//            return adjustmentMap.get(MusicDto.Adjustment.END.name()) * SECOND_MULTIPLIER;
//        }
        return 0L;
    }

    public static boolean isUrl(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static int deriveDeleteDelayFromTrack(AudioTrack track) {
        return (int) (track.getDuration() / 1000);
    }

    private static void resetNowPlayingMessage(long guildId) {
        Map<Channel, Message> channelMessageMap = guildLastNowPlayingMessage.get(guildId);
        if (channelMessageMap != null && !channelMessageMap.isEmpty())
            channelMessageMap.values().forEach(message -> message.delete().queue());
        guildLastNowPlayingMessage.remove(guildId);
    }


    public static void resetMessages(long guildId) {
        resetNowPlayingMessage(guildId);
    }

}
