package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.VoiceChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

public class TalkCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {
        final TextChannel channel = ctx.getChannel();

        final Member member = ctx.getMember();
        final GuildVoiceState memberVoiceState = member.getVoiceState();
        final VoiceChannel memberChannel = memberVoiceState.getChannel();

        memberChannel.getMembers().forEach(target -> {

        if (!member.canInteract(target) || !member.hasPermission(Permission.VOICE_MUTE_OTHERS) || !requestingUserDto.isSuperUser()) {
            channel.sendMessage(String.format("You aren't allowed to unmute %s", target)).queue();
            return;
        }

        final Member bot = ctx.getSelfMember();

        if (!bot.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
            channel.sendMessage(String.format("I'm not allowed to unmute %s", target)).queue();
            return;
        }

        ctx.getGuild()
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
    public String getHelp(String prefix) {
        return "Unmute everyone in your voice channel, mostly made for Among Us.\n" +
                String.format("Usage: `%stalk`", prefix);
    }
}