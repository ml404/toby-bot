package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;
import static toby.jpa.dto.ConfigDto.Configurations.*;

public class SetConfigCommand implements IModerationCommand {

    private final IConfigService configService;

    public SetConfigCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        if (!member.isOwner()) {
            event.getHook().sendMessage("This is currently reserved for the owner of the server only, this may change in future").setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay);
    }


    private void validateArgumentsAndUpdateConfigs(SlashCommandInteractionEvent event, Integer deleteDelay) {
        List<OptionMapping> options = event.getOptions();
        if (options.isEmpty()) {
            event.getHook().sendMessage(getDescription()).queue(getConsumer(deleteDelay));
            return;
        }
        options.forEach(optionMapping -> {
            switch (ConfigDto.Configurations.valueOf(optionMapping.getName())) {
                case MOVE -> setMove(event, deleteDelay);
                case VOLUME -> setConfigAndSendMessage(event, deleteDelay, VOLUME.name(), "Set default volume to '%s'");
                case DELETE_DELAY -> setConfigAndSendMessage(event, deleteDelay, DELETE_DELAY.name(), "Set default delete message delay for TobyBot music messages to '%d' seconds");
                default -> {
                }
            }
        });
    }
    private void setConfigAndSendMessage(SlashCommandInteractionEvent event, Integer deleteDelay, String propertyName, String messageToSend) {
        Optional<Integer> newValueOptional = Optional.ofNullable(event.getOption(propertyName)).map(OptionMapping::getAsInt);
        if (newValueOptional.isEmpty() || newValueOptional.get() < 0) {
            event.getHook().sendMessage("Value given valid (a whole number representing percent)").setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }
        Integer newDefaultVolume = newValueOptional.get();
        ConfigDto databaseConfig = configService.getConfigByName(propertyName, event.getGuild().getId());
        ConfigDto newConfigDto = new ConfigDto(propertyName, newDefaultVolume.toString(), event.getGuild().getId());
        if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
            configService.updateConfig(newConfigDto);
        } else {
            configService.createNewConfig(newConfigDto);
        }
        event.getHook().sendMessageFormat(messageToSend, newDefaultVolume).queue(getConsumer(deleteDelay));
    }

    private void setMove(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String movePropertyName = MOVE.name();
        Optional<GuildChannelUnion> newDefaultMoveChannelOptional = Optional.ofNullable(event.getOption(movePropertyName)).map(OptionMapping::getAsChannel);
        if (newDefaultMoveChannelOptional.isPresent()) {
            ConfigDto databaseConfig = configService.getConfigByName(movePropertyName, event.getGuild().getId());
            GuildChannelUnion newChannel = newDefaultMoveChannelOptional.get();
            ConfigDto newConfigDto = new ConfigDto(movePropertyName, newChannel.getName(), event.getGuild().getId());
            if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                configService.updateConfig(newConfigDto);
            } else {
                configService.createNewConfig(newConfigDto);
            }
            event.getHook().sendMessageFormat("Set default move channel to '%s'", newChannel.getName()).setEphemeral(true).queue(getConsumer(deleteDelay));
        }
        else {
            event.getHook().sendMessage("No valid channel was mentioned, so config was not updated").setEphemeral(true).queue(getConsumer(deleteDelay));
        }
    }


    @Override
    public String getName() {
        return "setconfig";
    }

    @Override
    public String getDescription() {
        return "Use this command to set the configuration used for this bot your server";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData defaultVolume = new OptionData(OptionType.INTEGER, VOLUME.name().toLowerCase(), "Default volume for audio player on your server (100 without an override)", false);
        OptionData deleteDelay = new OptionData(OptionType.INTEGER, DELETE_DELAY.name().toLowerCase(), "The length of time in seconds before your slash command messages are deleted", false);
        OptionData channelValue = new OptionData(OptionType.CHANNEL, MOVE.name().toLowerCase(), "Value for the default move channel you want if using move command without arguments", false);
        return List.of(defaultVolume, deleteDelay, channelValue);
    }
}
