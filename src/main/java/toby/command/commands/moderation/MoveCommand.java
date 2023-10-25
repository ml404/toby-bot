package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;

public class MoveCommand implements IModerationCommand {

    private final IConfigService configService;
    private final String USERS = "users";
    private final String CHANNEL = "channel";

    public MoveCommand(IConfigService configService) {
        this.configService = configService;
    }


    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        Guild guild = event.getGuild();

        List<Member> memberList = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getMentions).map(Mentions::getMembers).orElse(Collections.emptyList());
        if (memberList.isEmpty()) {
            event.getHook().sendMessage("You must mention 1 or more Users to move").queue(getConsumer(deleteDelay));
            return;
        }

        Optional<GuildChannelUnion> channelOptional = Optional.ofNullable(event.getOption(CHANNEL)).map(OptionMapping::getAsChannel);
        ConfigDto channelConfig = configService.getConfigByName(ConfigDto.Configurations.MOVE.getConfigValue(), guild.getId());
        Optional<VoiceChannel> voiceChannelOptional = channelOptional.map(GuildChannelUnion::asVoiceChannel).or(() -> guild.getVoiceChannelsByName(channelConfig.getValue(), true).stream().findFirst());
        if (voiceChannelOptional.isEmpty()) {
            event.getHook().sendMessageFormat("Could not find a channel on the server that matched name").queue(getConsumer(deleteDelay));
            return;
        }

        memberList.forEach(target -> {
            if (doChannelValidation(ctx.getEvent(), guild.getSelfMember(), member, target, deleteDelay)) return;
            VoiceChannel voiceChannel = voiceChannelOptional.get();
            guild.moveVoiceMember(target, voiceChannel)
                    .queue(
                            (__) -> event.getHook().sendMessageFormat("Moved %s to '%s'", target.getEffectiveName(), voiceChannel.getName()).queue(getConsumer(deleteDelay)),
                            (error) -> event.getHook().sendMessageFormat("Could not move '%s'", error.getMessage()).queue(getConsumer(deleteDelay))
                    );
        });
    }

    private boolean doChannelValidation(SlashCommandInteractionEvent event, Member botMember, Member member, Member target, int deleteDelay) {
        if (!target.getVoiceState().inAudioChannel()) {
            event.getHook().sendMessageFormat("Mentioned user '%s' is not connected to a voice channel currently, so cannot be moved.", target.getEffectiveName()).queue(getConsumer(deleteDelay));
            return true;
        }
        if (!member.canInteract(target) || !member.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            event.getHook().sendMessageFormat("You can't move '%s'", target.getEffectiveName()).queue(getConsumer(deleteDelay));
            return true;
        }
        if (!botMember.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            event.getHook().sendMessageFormat("I'm not allowed to move %s", target.getEffectiveName()).queue(getConsumer(deleteDelay));
            return true;
        }
        return false;
    }

    @Override
    public String getName() {
        return "move";
    }

    @Override
    public String getDescription() {
        return "Move mentioned members into a voice channel (voice channel can be defaulted by config command)";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(
                new OptionData(OptionType.STRING, USERS, "User(s) to move", true),
                new OptionData(OptionType.STRING, CHANNEL, "Channel to move to"));
    }
}