package toby.command.commands.music;


import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.IMusicCommand.isInvalidChannelStateForCommand;
import static toby.helpers.MusicPlayerHelper.changePauseStatusOnTrack;


public class ResumeCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }
        if (isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        changePauseStatusOnTrack(event,instance.getMusicManager(ctx.getGuild()), deleteDelay);
    }

    @Override
    public String getName() {
        return "resume";
    }

    @Override
    public String getDescription() {
        return "Resumes the current song if one is paused.";
    }
}
