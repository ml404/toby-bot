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

import static toby.command.ICommand.invokeDeleteOnMessageResponse;
import static toby.jpa.dto.ConfigDto.Configurations.*;

public class SetConfigCommand implements IModerationCommand {

    private final IConfigService configService;

    public SetConfigCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        if (!member.isOwner()) {
            event.getHook().sendMessage("This is currently reserved for the owner of the server only, this may change in future").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay);
    }


    private void validateArgumentsAndUpdateConfigs(SlashCommandInteractionEvent event, Integer deleteDelay) {
        List<OptionMapping> options = event.getOptions();
        if (options.isEmpty()) {
            event.getHook().sendMessage(getDescription()).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }
        options.forEach(optionMapping -> {
            switch (ConfigDto.Configurations.valueOf(optionMapping.getName().toUpperCase())) {
                case MOVE -> setMove(event, deleteDelay);
                case VOLUME -> setConfigAndSendMessage(event, optionMapping, deleteDelay, "Set default volume to '%s'");
                case DELETE_DELAY -> setConfigAndSendMessage(event, optionMapping, deleteDelay, "Set default delete message delay for TobyBot music messages to '%d' seconds");
                default -> {
                }
            }
        });
    }
    private void setConfigAndSendMessage(SlashCommandInteractionEvent event, OptionMapping optionMapping, Integer deleteDelay, String messageToSend) {
        Optional<Integer> newValueOptional = Optional.ofNullable(optionMapping).map(OptionMapping::getAsInt);
        if (newValueOptional.isEmpty() || newValueOptional.get() < 0) {
            event.getHook().sendMessage("Value given invalid (a whole number representing percent)").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
            return;
        }
        String configValue = valueOf(optionMapping.getName().toUpperCase()).getConfigValue();
        ConfigDto databaseConfig = configService.getConfigByName(configValue, event.getGuild().getId());
        int newDefaultVolume = optionMapping.getAsInt();
        ConfigDto newConfigDto = new ConfigDto(configValue, String.valueOf(newDefaultVolume), event.getGuild().getId());
        if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
            configService.updateConfig(newConfigDto);
        } else {
            configService.createNewConfig(newConfigDto);
        }
        event.getHook().sendMessageFormat(messageToSend, newDefaultVolume).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }

    private void setMove(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String movePropertyName = MOVE.getConfigValue();
        Optional<GuildChannelUnion> newDefaultMoveChannelOptional = Optional.ofNullable(event.getOption(MOVE.name().toLowerCase())).map(OptionMapping::getAsChannel);
        if (newDefaultMoveChannelOptional.isPresent()) {
            ConfigDto databaseConfig = configService.getConfigByName(movePropertyName, event.getGuild().getId());
            GuildChannelUnion newChannel = newDefaultMoveChannelOptional.get();
            ConfigDto newConfigDto = new ConfigDto(movePropertyName, newChannel.getName(), event.getGuild().getId());
            if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                configService.updateConfig(newConfigDto);
            } else {
                configService.createNewConfig(newConfigDto);
            }
            event.getHook().sendMessageFormat("Set default move channel to '%s'", newChannel.getName()).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
        }
        else {
            event.getHook().sendMessage("No valid channel was mentioned, so config was not updated").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
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
