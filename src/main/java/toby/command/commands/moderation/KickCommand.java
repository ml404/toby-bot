package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;

import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.invokeDeleteOnMessageResponse;

public class KickCommand implements IModerationCommand {

    private final String USERS = "users";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Guild guild = event.getGuild();
        final Member member = ctx.getMember();
        final Member botMember = guild.getSelfMember();

        Optional<List<Member>> optionalMemberList = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getMentions).map(Mentions::getMembers);
        if (optionalMemberList.isEmpty()) {
            event.getHook().sendMessage("You must mention 1 or more Users to shoot").queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }

        optionalMemberList.get().forEach(target -> {

            if (!member.canInteract(target) || !member.hasPermission(Permission.KICK_MEMBERS)) {
                event.getHook().sendMessageFormat("You can't kick %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay));
                return;
            }


            if (!botMember.canInteract(target) || !botMember.hasPermission(Permission.KICK_MEMBERS)) {
                event.getHook().sendMessageFormat("I'm not allowed to kick %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay));
                return;
            }

            guild.kick(target).reason("because you told me to.").queue(
                    (__) -> event.getHook().sendMessage("Shot hit the mark... something about fortnite?").queue(invokeDeleteOnMessageResponse(deleteDelay)),
                    (error) -> event.getHook().sendMessageFormat("Could not kick %s", error.getMessage()).queue(invokeDeleteOnMessageResponse(deleteDelay)));
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