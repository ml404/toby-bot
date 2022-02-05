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

import java.util.Arrays;

public class MusicPlayerHelper {

    public static void playUserIntro(UserDto dbUser, Guild guild, TextChannel channel, int deleteDelay) {
        MusicDto musicDto = dbUser.getMusicDto();
        PlayerManager instance = PlayerManager.getInstance();
        int currentVolume = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().getVolume();
        if (musicDto != null && musicDto.getFileName() != null) {
            Integer introVolume = musicDto.getIntroVolume();
            changeVolumeForIntro(channel, deleteDelay, currentVolume, introVolume);
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            instance.setPlayingIntro(true);
            instance.loadAndPlay(guild.getSystemChannel(),
                    String.format(ConsumeWebService.getWebUrl() + "/music?id=%s", musicDto.getId()),
                    true,
                    true,
                    0);
        } else if (musicDto != null) {
            Integer introVolume = musicDto.getIntroVolume();
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(introVolume != null ? introVolume : currentVolume);
            changeVolumeForIntro(channel, deleteDelay, currentVolume, introVolume);
            instance.setPlayingIntro(true);
            instance.loadAndPlay(guild.getSystemChannel(), Arrays.toString(dbUser.getMusicDto().getMusicBlob()), true, true, 0);
        }
    }

    private static void changeVolumeForIntro(TextChannel channel, int deleteDelay, int currentVolume, Integer introVolume) {
        if (introVolume != null && currentVolume != introVolume)
            channel.sendMessageFormat("Changing volume from '%s' to intro volume '%s' \uD83D\uDD0A", currentVolume, introVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    public static void nowPlaying(TextChannel channel, AudioTrack track, Integer deleteDelay) {
        AudioTrackInfo info = track.getInfo();
        long duration = track.getDuration();
        String songDuration = QueueCommand.formatTime(duration);
        String nowPlaying = String.format("Now playing `%s` by `%s` `[%s]` (Link: <%s>) ", info.title, info.author, songDuration, info.uri);
        channel.sendMessage(nowPlaying).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }
}
