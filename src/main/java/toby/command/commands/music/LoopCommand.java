package toby.command.commands.music;


import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

import static toby.command.ICommand.deleteAfter;

public class LoopCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        deleteAfter(event.getHook(), deleteDelay);
        event.deferReply().queue();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        TrackScheduler scheduler = instance.getMusicManager(ctx.getGuild()).getScheduler();
        boolean newIsRepeating = !scheduler.isLooping();
        scheduler.setLooping(newIsRepeating);

        event.getHook().sendMessageFormat("The Player has been set to **%s**", newIsRepeating ? "looping" : "not looping").queue(ICommand.invokeDeleteOnMessageResponse(deleteDelay));
    }

    @Override
    public String getName() {
        return "loop";
    }

    @Override
    public String getDescription() {
        return "Loop the currently playing song";
    }
}
