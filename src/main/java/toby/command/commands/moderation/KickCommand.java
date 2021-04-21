package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;

import java.util.Arrays;
import java.util.List;

public class KickCommand implements IModerationCommand {
    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        final Member member = ctx.getMember();
        final List<String> args = ctx.getArgs();

        if (message.getMentionedMembers().isEmpty()) {
            channel.sendMessage("You must mention 1 or more Users to shoot").queue();
            return;
        }

        message.getMentionedMembers().forEach(target -> {

        if (!member.canInteract(target) || !member.hasPermission(Permission.KICK_MEMBERS)) {
            channel.sendMessage(String.format("You can't shoot %s", target)).queue();
            return;
        }

        final Member botMember = ctx.getSelfMember();

        if (!botMember.canInteract(target) || !botMember.hasPermission(Permission.KICK_MEMBERS)) {
            channel.sendMessage(String.format("I'm not allowed to shoot %s", target)).queue();
            return;
        }

        ctx.getGuild()
                .kick(target)
                .reason("because you told me to.")
                .queue(
                        (__) -> channel.sendMessage("Shot hit the mark... something about fortnite?").queue(),
                        (error) -> channel.sendMessageFormat("Could not shoot %s", error.getMessage()).queue()
                );
        });
    }

    @Override
    public String getName() {
        return "kick";
    }

    @Override
    public String getHelp(String prefix) {
        return "Kick a member off the server.\n" +
                String.format("Usage: `%skick <@user>`", prefix)+
                String.format("Aliases are: '%s'",String.join(",", getAliases()));

    }

    public List<String> getAliases(){
        return Arrays.asList("shoot");
    }
}