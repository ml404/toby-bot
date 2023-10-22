package toby.command.commands.music;


import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.lavaplayer.TrackScheduler;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;

public class LoopCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return;

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        TrackScheduler scheduler = musicManager.getScheduler();
        boolean newIsRepeating = !scheduler.isLooping();
        scheduler.setLooping(newIsRepeating);

        event.getHook().sendMessageFormat("The Player has been set to **%s**", newIsRepeating ? "looping" : "not looping").queue(getConsumer(deleteDelay));
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
