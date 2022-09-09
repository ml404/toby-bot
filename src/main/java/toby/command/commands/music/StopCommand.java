package toby.command.commands.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.NowDigOnThisCommand.sendDeniedStoppableMessage;


public class StopCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if(IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        final GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());

        if (PlayerManager.getInstance().isCurrentlyStoppable() || requestingUserDto.isSuperUser()) {
            musicManager.getScheduler().stopTrack(true);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getScheduler().setLooping(false);
            musicManager.getAudioPlayer().setPaused(false);
            event.getHook().sendMessage("The player has been stopped and the queue has been cleared").queue(message -> ICommand.deleteAfter(message, deleteDelay));
        } else {
            sendDeniedStoppableMessage(event, musicManager, deleteDelay);
        }
    }

    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public String getDescription() {
        return "Stops the current song and clears the queue";
    }
}
