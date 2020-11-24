package toby.command.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import toby.BotConfig;
import toby.command.CommandContext;
import toby.command.ICommand;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MoveCommand implements ICommand {
    @Override
    public void handle(CommandContext ctx) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        final Member member = ctx.getMember();
        final List<String> args = ctx.getArgs();
        VoiceChannel voiceChannel;
        Guild guild = ctx.getGuild();

        if (message.getMentionedMembers().isEmpty()) {
            channel.sendMessage("You must mention 1 or more Users to move").queue();
            return;
        }

        Optional<VoiceChannel> voiceChannelOptional = args.stream().map(guild::getVoiceChannelById).collect(Collectors.toList()).stream().findFirst();
        voiceChannel = voiceChannelOptional.isEmpty() ? guild.getVoiceChannelById(args.stream().map(s -> BotConfig.channelMap.get(s)).findFirst().orElse(756262044491055165L)) : voiceChannelOptional.get();

        message.getMentionedMembers().forEach(target -> {

            if (!member.canInteract(target) || !member.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
                channel.sendMessage(String.format("You can't move '%s'", target.getEffectiveName())).queue();
                return;
            }

            final Member botMember = ctx.getSelfMember();

            if (!botMember.canInteract(target) || !botMember.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
                channel.sendMessage(String.format("I'm not allowed to move %s", target.getEffectiveName())).queue();
                return;
            }

            guild
                    .moveVoiceMember(target, voiceChannel)
                    .queue(
                            (__) -> channel.sendMessageFormat("Moved %s to '%s'", target.getEffectiveName(), voiceChannel).queue(),
                            (error) -> channel.sendMessageFormat("Could not move '%s'", error.getMessage()).queue()
                    );
        });
    }

    @Override
    public String getName() {
        return "move";
    }

    @Override
    public String getHelp() {
        return "move a member into a voice channel.\n" +
                "Usage: `!move <@user> channelId`\n" +
                "e.g. !move @FratLayton 756262044491055165";
    }
}