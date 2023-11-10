package toby.command.commands.dnd;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.command.commands.misc.RollCommand;
import toby.jpa.dto.UserDto;

import java.util.HashMap;
import java.util.Map;

public class InitiativeCommand implements IDnDCommand{
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        Guild guild = event.getGuild();
        Map<Member, String> initiativeMap = new HashMap<>();
        member.getVoiceState().getChannel().getMembers().forEach(target -> {
            StringBuilder stringBuilder = RollCommand.buildStringForDiceRoll(20, 1, 0);
            initiativeMap.put(target, stringBuilder.toString());
        });
    }

    @Override
    public String getName() {
        return "initiative";
    }

    @Override
    public String getDescription() {
        return "Roll initiative for the mentioned members. Defaults to the voice channel connected members of the person who calls this command";
    }
}
