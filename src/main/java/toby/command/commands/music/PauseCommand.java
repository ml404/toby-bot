package toby.command.commands.music;


import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;

import static toby.command.commands.music.IMusicCommand.isInvalidChannelStateForCommand;
import static toby.command.commands.music.IMusicCommand.sendDeniedStoppableMessage;
import static toby.helpers.MusicPlayerHelper.changePauseStatusOnTrack;

public class PauseCommand implements IMusicCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        handleMusicCommand(ctx, PlayerManager.getInstance(), requestingUserDto, deleteDelay);
    }

    @Override
    public void handleMusicCommand(CommandContext ctx, PlayerManager instance, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        if (!requestingUserDto.hasMusicPermission()) {
            sendErrorMessage(event, deleteDelay);
            return;
        }
        if (isInvalidChannelStateForCommand(ctx, deleteDelay)) return;
        final Member member = ctx.getMember();
        Guild guild = event.getGuild();

        GuildMusicManager musicManager = instance.getMusicManager(guild);
        if (instance.isCurrentlyStoppable() || member.hasPermission(Permission.KICK_MEMBERS)) {
            changePauseStatusOnTrack(event.getHook(), musicManager, deleteDelay);
        } else {
            sendDeniedStoppableMessage(event.getHook(), musicManager, deleteDelay);
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
