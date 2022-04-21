package toby.helpers;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.ICommand;
import toby.command.commands.music.QueueCommand;
import toby.jpa.controller.ConsumeWebService;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MusicPlayerHelper {

    public static final int SECOND_MULTIPLIER = 1000;

    public static void playUserIntro(UserDto dbUser, Guild guild, TextChannel channel, int deleteDelay, Long startPosition) {
        MusicDto musicDto = dbUser.getMusicDto();
        PlayerManager instance = PlayerManager.getInstance();
        int currentVolume = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().getVolume();
        if (musicDto != null && musicDto.getFileName() != null) {
            Integer introVolume = musicDto.getIntroVolume();
            instance.setPreviousVolume(currentVolume);
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            instance.loadAndPlay(channel,
                    String.format(ConsumeWebService.getWebUrl() + "/music?id=%s", musicDto.getId()),
                    true,
                    0,
                    startPosition);
        } else if (musicDto != null) {
            Integer introVolume = musicDto.getIntroVolume();
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            instance.setPreviousVolume(currentVolume);
            instance.loadAndPlay(channel, Arrays.toString(dbUser.getMusicDto().getMusicBlob()), true, deleteDelay, startPosition);
        }
    }

    public static void nowPlaying(TextChannel channel, AudioTrack track, Integer deleteDelay) {
        AudioTrackInfo info = track.getInfo();
        long duration = track.getDuration();
        String songDuration = QueueCommand.formatTime(duration);
        String nowPlaying = String.format("Now playing `%s` by `%s` `[%s]` (Link: <%s>) ", info.title, info.author, songDuration, info.uri);
        channel.sendMessage(nowPlaying).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    public static Long adjustTrackPlayingTimes(List<String> args, TextChannel channel, Integer deleteDelay) {
        List<String> valuesToAdjust = args.stream().filter(s -> !isUrl(s)).collect(Collectors.toList());
        Map<String, Long> permissionMap = valuesToAdjust.stream()
                .map(s -> s.split("=", 2))
                .filter(strings -> MusicDto.Adjustment.isValidEnum(strings[0].toUpperCase()) && (strings[1] != null && (strings[1].equalsIgnoreCase("false") || strings[1].equalsIgnoreCase("true"))))
                .collect(Collectors.toMap(s -> s[0], s -> Long.valueOf(s[1])));

        if (permissionMap.isEmpty()) {
            channel.sendMessage("You did not mention a valid permission to update").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return 0L;
        }

        if (permissionMap.containsKey(MusicDto.Adjustment.START.name())){
            return permissionMap.get(MusicDto.Adjustment.START.name()) * SECOND_MULTIPLIER;
        }
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
