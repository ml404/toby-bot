package toby.command.commands.music;


import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

public class LoopCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        TrackScheduler scheduler = musicManager.getScheduler();
        boolean newIsRepeating = !scheduler.isLooping();
        scheduler.setLooping(newIsRepeating);

        event.replyFormat("The Player has been set to **%s**", newIsRepeating ? "looping" : "not looping").queue(message -> ICommand.deleteAfter(message, deleteDelay));
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
