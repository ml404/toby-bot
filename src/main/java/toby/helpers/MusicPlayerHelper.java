package toby.helpers;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.commands.music.QueueCommand;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static toby.command.ICommand.getConsumer;

public class MusicPlayerHelper {

    private static final String webUrl = "https://gibe-toby-bot.herokuapp.com/";

    public static final int SECOND_MULTIPLIER = 1000;

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
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlay(event, Arrays.toString(dbUser.getMusicDto().getMusicBlob()), true, deleteDelay, startPosition, volume);
        }
    }

    public static void playUserIntroWithChannel(UserDto dbUser, Guild guild, TextChannel channel, int deleteDelay, Long startPosition) {
        MusicDto musicDto = dbUser.getMusicDto();
        PlayerManager instance = PlayerManager.getInstance();
        int currentVolume = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().getVolume();
        if (musicDto != null && musicDto.getFileName() != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            instance.loadAndPlayChannel(channel,
                    String.format(webUrl + "/music?id=%s", musicDto.getId()),
                    true,
                    0,
                    startPosition);
        } else if (musicDto != null) {
            Integer introVolume = musicDto.getIntroVolume();
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlayChannel(channel, Arrays.toString(dbUser.getMusicDto().getMusicBlob()), true, deleteDelay, startPosition);
        }
    }

    public static void nowPlaying(SlashCommandInteractionEvent event, AudioTrack track, Integer deleteDelay, int volume) {
        AudioTrackInfo info = track.getInfo();
        long duration = track.getDuration();
        String songDuration = QueueCommand.formatTime(duration);
        String nowPlaying = String.format("Now playing `%s` by `%s` `[%s]` (Link: <%s>) with volume `%d`", info.title, info.author, songDuration, info.uri, volume);
        event.getHook().sendMessage(nowPlaying).queue(getConsumer(deleteDelay));
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
}
