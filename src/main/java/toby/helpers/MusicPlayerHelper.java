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

    public static void playUserIntro(UserDto dbUser, Guild guild) {
        MusicDto musicDto = dbUser.getMusicDto();
        if (musicDto != null && musicDto.getFileName() != null) {
            PlayerManager.getInstance().loadAndPlay(guild.getSystemChannel(),
                    String.format(ConsumeWebService.getWebUrl() + "/music?id=%s", musicDto.getId()),
                    0);
        } else if (musicDto != null) {
            PlayerManager.getInstance().loadAndPlay(guild.getSystemChannel(), Arrays.toString(dbUser.getMusicDto().getMusicBlob()),
                    0);
        }
    }

    public static void nowPlaying(TextChannel channel, AudioTrack track, Integer deleteDelay) {
        AudioTrackInfo info = track.getInfo();
        long duration = track.getDuration();
        String songDuration = QueueCommand.formatTime(duration);
        String nowPlaying = String.format("Now playing `%s` by `%s` `[%s]` (Link: <%s>) ", info.title, info.author, songDuration, info.uri);
        channel.sendMessage(nowPlaying).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }
}
