package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.AudioChannel;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

public class TalkCommand implements IModerationCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();
        final AudioChannel memberChannel = memberVoiceState.getChannel();

        memberChannel.getMembers().forEach(target -> {

        if (!member.canInteract(target) || !member.hasPermission(Permission.VOICE_MUTE_OTHERS) || !requestingUserDto.isSuperUser()) {
            event.replyFormat("You aren't allowed to unmute %s", target).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        final Member bot = ctx.getSelfMember();

        if (!bot.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
            event.replyFormat("I'm not allowed to unmute %s", target).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        event.getGuild()
                .mute(target, false)
                .reason("Unmuted for Among Us.")
                .queue();
        });
    }

    @Override
    public String getName() {
        return "talk";
    }

    @Override
    public String getDescription() {
        return "Unmute everyone in your voice channel, mostly made for Among Us.";
    }
}