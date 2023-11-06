package toby.command.commands.music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.ICommand.*;

public interface IMusicCommand extends ICommand {

    static void sendDeniedStoppableMessage(SlashCommandInteractionEvent event, GuildMusicManager musicManager, Integer deleteDelay) {
        if (musicManager.getScheduler().getQueue().size() > 0) {
            event.getHook().sendMessage("Our daddy taught us not to be ashamed of our playlists").queue(invokeDeleteOnMessageResponse(deleteDelay));
        } else {
            long duration = musicManager.getAudioPlayer().getPlayingTrack().getDuration();
            String songDuration = QueueCommand.formatTime(duration);
            event.getHook().sendMessageFormat("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!", songDuration, songDuration).queue(invokeDeleteOnMessageResponse(deleteDelay));
        }
    }

    void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay);

    static boolean isInvalidChannelStateForCommand(CommandContext ctx, Integer deleteDelay) {
        final Member self = ctx.getSelfMember();
        final GuildVoiceState selfVoiceState = self.getVoiceState();
        SlashCommandInteractionEvent event = ctx.getEvent();
        if (!selfVoiceState.inAudioChannel()) {
            event.getHook().sendMessage("I need to be in a voice channel for this to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return true;
        }

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();
        if (!memberVoiceState.inAudioChannel()) {
            event.getHook().sendMessage("You need to be in a voice channel for this command to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return true;
        }

        if (!memberVoiceState.getChannel().equals(selfVoiceState.getChannel())) {
            event.getHook().sendMessage("You need to be in the same voice channel as me for this to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return true;
        }
        return false;
    }

}

