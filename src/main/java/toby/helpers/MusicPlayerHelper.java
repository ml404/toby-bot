package toby.helpers;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import toby.command.commands.music.IMusicCommand;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

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
    private static final Map<Long, WebhookMessageCreateAction<Message>> guildLastNowPlayingMessageAction = new HashMap<>();

    public static void playUserIntroWithEvent(UserDto dbUser, Guild guild, SlashCommandInteractionEvent event, int deleteDelay, Long startPosition, int volume) {
        MusicDto musicDto = dbUser.getMusicDto();
        PlayerManager instance = PlayerManager.getInstance();
        int currentVolume = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().getVolume();
        if (musicDto != null && musicDto.getFileName() != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            instance.loadAndPlay(event,
                    String.format(webUrl + "/music?id=%s", musicDto.getId()),
                    true,
                    0,
                    startPosition,
                    volume);
        } else if (musicDto != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlay(event, Arrays.toString(dbUser.getMusicDto().getMusicBlob()), true, deleteDelay, startPosition, introVolume);
        }
    }

    public static void playUserIntroWithChannel(UserDto dbUser, Guild guild, TextChannel channel, int deleteDelay, Long startPosition) {
        MusicDto musicDto = dbUser.getMusicDto();
        PlayerManager instance = PlayerManager.getInstance();
        int currentVolume = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().getVolume();
        if (musicDto != null && musicDto.getFileName() != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlayChannel(channel,
                    String.format(webUrl + "/music?id=%s", musicDto.getId()),
                    true,
                    0,
                    introVolume,
                    startPosition);
        } else if (musicDto != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlayChannel(channel, Arrays.toString(dbUser.getMusicDto().getMusicBlob()), true, deleteDelay, introVolume, startPosition);
        }
    }

    public static void nowPlaying(IReplyCallback event, PlayerManager playerManager, Integer deleteDelay) {
        GuildMusicManager musicManager = playerManager.getMusicManager(event.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();
        AudioTrack track = audioPlayer.getPlayingTrack();
        InteractionHook hook = event.getHook();
        if (checkForPlayingTrack(track, hook, deleteDelay)) return;
        checkTrackAndSendMessage(track, hook, (int) track.getUserData());
    }

    public static void nowPlaying(IReplyCallback event, PlayerManager playerManager, int volume, Integer deleteDelay) {
        GuildMusicManager musicManager = playerManager.getMusicManager(event.getGuild());
        final AudioPlayer audioPlayer = musicManager.getAudioPlayer();
        AudioTrack track = audioPlayer.getPlayingTrack();
        InteractionHook hook = event.getHook();
        if (checkForPlayingTrack(track, hook, deleteDelay)) return;
        checkTrackAndSendMessage(track, hook, volume);
    }

    private static void checkTrackAndSendMessage(AudioTrack track, InteractionHook hook, int volume) {
        String nowPlaying = getNowPlayingString(track, volume);
        Button pausePlay = Button.primary("pause/play", "⏯");
        Button stop = Button.primary("stop", "⏹");
        long guildId = hook.getInteraction().getGuild().getIdLong();
        resetNowPlayingMessage(guildId);
        WebhookMessageCreateAction<Message> nowPlayingMessageAction = hook.sendMessage(nowPlaying).addActionRow(pausePlay, stop);
        nowPlayingMessageAction.queue(invokeDeleteOnMessageResponse(deriveDeleteDelayFromTrack(track)));
        guildLastNowPlayingMessageAction.put(guildId, nowPlayingMessageAction);
    }

    private static boolean checkForPlayingTrack(AudioTrack track, InteractionHook hook, Integer deleteDelay) {
        if (track == null) {
            hook.sendMessage("There is no track playing currently").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return true;
        }
        return false;
    }

    public static void stopSong(IReplyCallback event, GuildMusicManager musicManager, boolean canOverrideSkips, Integer deleteDelay) {
        InteractionHook hook = event.getHook();
        if (PlayerManager.getInstance().isCurrentlyStoppable() || canOverrideSkips) {
            musicManager.getScheduler().stopTrack(true);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getScheduler().setLooping(false);
            musicManager.getAudioPlayer().setPaused(false);
            hook.deleteOriginal().queue();
            hook.sendMessage("The player has been stopped and the queue has been cleared").queue(invokeDeleteOnMessageResponse(deleteDelay));
            resetNowPlayingMessage(event.getGuild().getIdLong());
        } else {
            IMusicCommand.sendDeniedStoppableMessage(hook, musicManager, deleteDelay);
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

    public static void changePauseStatusOnTrack(IReplyCallback event, GuildMusicManager musicManager, Integer deleteDelay) {
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
            AudioTrack playingTrack = musicManager.getAudioPlayer().getPlayingTrack();
            nowPlaying(event, playerManager, (int) playingTrack.getUserData(), deleteDelay);
        }
        sendDeniedStoppableMessage(hook, musicManager, deleteDelay);
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
        WebhookMessageCreateAction<Message> nowPlayingMessageAction = guildLastNowPlayingMessageAction.get(guildId);
        if (nowPlayingMessageAction != null) {
            deleteAndResetNowPlayingMessage(guildId, nowPlayingMessageAction);
        }
    }

    private static void deleteAndResetNowPlayingMessage(long guildId, WebhookMessageCreateAction<Message> nowPlayingMessageAction) {
        nowPlayingMessageAction.complete().delete().queue();
        guildLastNowPlayingMessageAction.remove(guildId);
    }
}
