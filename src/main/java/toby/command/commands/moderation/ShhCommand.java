package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.invokeDeleteOnMessageResponse;

public class ShhCommand implements IModerationCommand {
    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        Guild guild = event.getGuild();
        member.getVoiceState().getChannel().getMembers().forEach(target -> {
            if (!member.canInteract(target) || !member.hasPermission(Permission.VOICE_MUTE_OTHERS) || !requestingUserDto.isSuperUser()) {
                event.getHook().sendMessageFormat("You aren't allowed to mute %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay));
                return;
            }

            final Member bot = guild.getSelfMember();

            if (!bot.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
                event.getHook().sendMessageFormat("I'm not allowed to mute %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay));
                return;
            }
            guild.mute(target, true)
                    .reason("Muted")
                    .queue();
        });
    }

    @Override
    public String getName() {
        return "shh";
    }

    @Override
    public String getDescription() {
        return "Silence everyone in your voice channel, please only use for Among Us.\n" +
                String.format("Usage: `%sshh`", "/");
    }
}