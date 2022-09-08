package toby.command.commands.music;


import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;

public class PauseCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        final Member member = ctx.getMember();
        Guild guild = event.getGuild();
        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        if (PlayerManager.getInstance().isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
            if (!audioPlayer.isPaused()) {
                AudioTrack track = audioPlayer.getPlayingTrack();
                event.reply("Pausing: `")
                        .addContent(track.getInfo().title)
                        .addContent("` by `")
                        .addContent(track.getInfo().author)
                        .addContent("`")
                        .queue(message -> ICommand.deleteAfter(message, deleteDelay));
                audioPlayer.setPaused(true);
            } else {
                event.replyFormat("The audio player on this server is already paused. Please try using %sresume", "/").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            }
        } else {
            sendDeniedStoppableMessage(event, musicManager, deleteDelay);
        }
    }

    @Override
    public String getName() {
        return "pause";
    }

    @Override
    public String getDescription() {
        return "Pauses the current song if one is playing";
    }

}
