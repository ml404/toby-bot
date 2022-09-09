package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.List;

public class KickCommand implements IModerationCommand {

    private final String USERS = "users";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();

        List<Member> memberOptions = event.getOption(USERS).getMentions().getMembers();
        if (memberOptions.isEmpty()) {
            event.getHook().sendMessage("You must mention 1 or more Users to shoot").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return;
        }

        memberOptions.forEach(target -> {

            if (!member.canInteract(target) || !member.hasPermission(Permission.KICK_MEMBERS)) {
                event.replyFormat("You can't kick %s", target).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                return;
            }

            final Member botMember = ctx.getSelfMember();

            if (!botMember.canInteract(target) || !botMember.hasPermission(Permission.KICK_MEMBERS)) {
                event.replyFormat("I'm not allowed to kick %s", target).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                return;
            }

            event.getGuild()
                    .kick(target)
                    .reason("because you told me to.")
                    .queue(
                            (__) -> event.getHook().sendMessage("Shot hit the mark... something about fortnite?").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay)),
                            (error) -> event.replyFormat("Could not kick %s", error.getMessage()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay))
                    );
        });
    }

    @Override
    public String getName() {
        return "kick";
    }

    @Override
    public String getDescription() {
        return "Kick a member off the server.";

    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(new OptionData(OptionType.STRING, USERS, "User(s) to kick", true));
    }
}