package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import toby.command.CommandContext;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class MoveCommand implements IModerationCommand {

    private final IConfigService configService;

    public MoveCommand(IConfigService configService) {
        this.configService = configService;
    }


    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        final Member member = ctx.getMember();
        final List<String> args = ctx.getArgs();
        Guild guild = ctx.getGuild();

        if (message.getMentionedMembers().isEmpty()) {
            channel.sendMessage("You must mention 1 or more Users to move").queue();
            return;
        }

        List<String> prefixlessList = args.stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).collect(Collectors.toList());
        String channelName = String.join(" ", prefixlessList);
        ConfigDto channelConfig = configService.getConfigByName("DEFAULT_MOVE_CHANNEL", guild.getId());

        Optional<VoiceChannel> voiceChannelOptional = (!channelName.isBlank()) ? guild.getVoiceChannelsByName(channelName, true).stream().findFirst() : guild.getVoiceChannelsByName(channelConfig.getValue(), true).stream().findFirst();
        if (!voiceChannelOptional.isPresent()) {
            channel.sendMessageFormat("Could not find a channel on the server that matched name '%s'", channelName).queue();
            return;
        }
        message.getMentionedMembers().forEach(target -> {

            if (doChannelValidation(ctx, channel, member, target)) return;

            VoiceChannel voiceChannel = voiceChannelOptional.get();
            guild.moveVoiceMember(target, voiceChannel)
                    .queue(
                            (__) -> channel.sendMessageFormat("Moved %s to '%s'", target.getEffectiveName(), voiceChannel.getName()).queue(),
                            (error) -> channel.sendMessageFormat("Could not move '%s'", error.getMessage()).queue()
                    );
        });
    }

    private boolean doChannelValidation(CommandContext ctx, TextChannel channel, Member member, Member target) {
        if (!target.getVoiceState().inVoiceChannel()) {
            channel.sendMessage(String.format("Mentioned user '%s' is not connected to a voice channel currently, so cannot be moved.", target.getEffectiveName())).queue();
            return true;
        }
        if (!member.canInteract(target) || !member.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            channel.sendMessage(String.format("You can't move '%s'", target.getEffectiveName())).queue();
            return true;
        }

        final Member botMember = ctx.getSelfMember();

        if (!botMember.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            channel.sendMessage(String.format("I'm not allowed to move %s", target.getEffectiveName())).queue();
            return true;
        }
        return false;
    }

    @Override
    public String getName() {
        return "move";
    }

    @Override
    public String getHelp(String prefix) {
        return "move a member into a voice channel.\n" +
                String.format("Usage: `%smove <@user> channel name`\n", prefix) +
                "e.g. `!move @username i have a bad opinion`";
    }
}