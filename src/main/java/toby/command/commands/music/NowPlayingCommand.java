package toby.command.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;


public class NowPlayingCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (requestingUserDto.hasMusicPermission()) {
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
            GuildMusicManager musicManager = instance.getMusicManager(ctx.getGuild());
            final AudioPlayer audioPlayer = musicManager.getAudioPlayer();
            final AudioTrack track = audioPlayer.getPlayingTrack();

            if (track == null) {
                event.getHook().sendMessage("There is no track playing currently").setEphemeral(true).queue(getConsumer(deleteDelay));
                return;
            }

            final AudioTrackInfo info = track.getInfo();

            AudioTrack playingTrack = musicManager.getAudioPlayer().getPlayingTrack();
            if (!track.getInfo().isStream) {
                long position = playingTrack.getPosition();
                long duration = playingTrack.getDuration();
                String songPosition = QueueCommand.formatTime(position);
                String songDuration = QueueCommand.formatTime(duration);
                String nowPlaying = String.format("Now playing `%s` by `%s` `[%s/%s]` (Link: <%s>) ", info.title, info.author, songPosition, songDuration, info.uri);
                event.getHook().sendMessage(nowPlaying).setEphemeral(true).queue(getConsumer(deleteDelay));
            } else {
                String nowPlaying = String.format("Now playing `%s` by `%s` (Link: <%s>) ", info.title, info.author, info.uri);
                event.getHook().sendMessage(nowPlaying).setEphemeral(true).queue(getConsumer(deleteDelay));
            }
        }
    }

    @Override
    public String getName() {
        return "nowplaying";
    }

    @Override
    public String getDescription() {
        return "Shows the currently playing song";
    }
}
