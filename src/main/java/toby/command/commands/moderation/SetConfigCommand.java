package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static toby.command.ICommand.deleteAfter;
import static toby.command.ICommand.getConsumer;
import static toby.jpa.dto.ConfigDto.Configurations.*;

public class SetConfigCommand implements IModerationCommand {

    private final IConfigService configService;
    private final String CONFIG_NAME = "name";
    private final String CONFIG_VALUE = "value";

    public SetConfigCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        List<OptionMapping> args = event.getOptions();
        final Member member = ctx.getMember();

        if (!member.isOwner()) {
            event.getHook().sendMessage("This is currently reserved for the owner of the server only, this may change in future").setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }

        if (args.isEmpty()) {
            event.getHook().sendMessage(getDescription()).queue(getConsumer(deleteDelay));
            return;
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay);
    }


    private void validateArgumentsAndUpdateConfigs(SlashCommandInteractionEvent event, Integer deleteDelay) {
        Optional<String> configNameStringOptional = Optional.ofNullable(event.getOption(CONFIG_NAME)).map(optionMapping -> optionMapping.getAsString().toUpperCase());

        if (configNameStringOptional.isEmpty()) {
            event.getHook().sendMessage(getDescription()).queue(getConsumer(deleteDelay));
            return;
        }

        ConfigDto.Configurations configName = valueOf(configNameStringOptional.get());

        switch (configName) {
            case PREFIX -> setPrefix(event, deleteDelay);
            case MOVE -> setMove(event, deleteDelay);
            case VOLUME -> setVolume(event, deleteDelay);
            case DELETE_DELAY -> setDeleteDelay(event, deleteDelay);
            default -> {
            }
        }
    }

    private void setDeleteDelay(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String newDefaultDelay = Optional.ofNullable(event.getOption(CONFIG_VALUE)).map(OptionMapping::getAsString).orElse("");
        if (!newDefaultDelay.matches("\\d+")) {
            event.getHook().sendMessage("Value given for default delete message delay for TobyBot music messages was not valid (a whole number representing seconds)").setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }
        String deletePropertyName = DELETE_DELAY.getConfigValue();
        ConfigDto databaseConfig = configService.getConfigByName(deletePropertyName, event.getGuild().getId());
        ConfigDto newConfigDto = new ConfigDto(deletePropertyName, newDefaultDelay, event.getGuild().getId());
        if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
            configService.updateConfig(newConfigDto);
        } else {
            configService.createNewConfig(newConfigDto);
        }
        event.getHook().sendMessageFormat("Set default delete message delay for TobyBot music messages to '%s' seconds", newDefaultDelay).queue(getConsumer(deleteDelay));
    }

    private void setVolume(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String newDefaultVolume = Optional.ofNullable(event.getOption(CONFIG_VALUE)).map(OptionMapping::getAsString).orElse("");
        if (!newDefaultVolume.matches("\\d+")) {
            event.getHook().sendMessage("Value given for default volume of TobyBot music was not valid (a whole number representing percent)").setEphemeral(true).queue(getConsumer(deleteDelay));
            return;
        }
        String volumePropertyName = VOLUME.getConfigValue();
        ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        ConfigDto newConfigDto = new ConfigDto(volumePropertyName, newDefaultVolume, event.getGuild().getId());
        if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
            configService.updateConfig(newConfigDto);
        } else {
            configService.createNewConfig(newConfigDto);
        }
        event.getHook().sendMessageFormat("Set default volume to '%s'", newDefaultVolume).queue(getConsumer(deleteDelay));
    }

    private void setMove(SlashCommandInteractionEvent event, Integer deleteDelay) {
        Optional<String> newDefaultMoveChannel = Optional.ofNullable(event.getOption(CONFIG_VALUE)).map(OptionMapping::getAsString);
        if (newDefaultMoveChannel.isPresent()) {
            boolean newDefaultVoiceChannelExists = !event.getGuild().getVoiceChannelsByName(newDefaultMoveChannel.get(), true).isEmpty();
            if (newDefaultVoiceChannelExists) {
                String movePropertyName = MOVE.getConfigValue();
                ConfigDto databaseConfig = configService.getConfigByName(movePropertyName, event.getGuild().getId());
                ConfigDto newConfigDto = new ConfigDto(movePropertyName, newDefaultMoveChannel.get(), event.getGuild().getId());
                if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                    configService.updateConfig(newConfigDto);
                } else {
                    configService.createNewConfig(newConfigDto);
                }
                event.getHook().sendMessageFormat("Set default move channel to '%s'", newDefaultMoveChannel).setEphemeral(true).queue(getConsumer(deleteDelay));
            }
        }
    }

    private void setPrefix(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String newPrefix = Optional.ofNullable(event.getOption(CONFIG_VALUE)).map(OptionMapping::getAsString).get();
        newPrefix = newPrefix.length() > 2 ? newPrefix.substring(0, 2) : newPrefix;
        if (prefixValidation(newPrefix)) {
            String prefixPropertyName = PREFIX.getConfigValue();
            ConfigDto databaseConfig = configService.getConfigByName(prefixPropertyName, event.getGuild().getId());
            ConfigDto newConfigDto = new ConfigDto(prefixPropertyName, newPrefix, event.getGuild().getId());

            if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                configService.updateConfig(newConfigDto);
            } else {
                configService.createNewConfig(newConfigDto);
            }
            event.getHook().sendMessageFormat("Set prefix to '%s'", newPrefix).setEphemeral(true).queue(getConsumer(deleteDelay));

        }
    }


    private boolean prefixValidation(String newPrefix) {
        boolean nonAlphanumericPrefix1 = newPrefix.matches("[^a-zA-Z\\d*\\s]");
        boolean nonAlphanumericPrefix2 = newPrefix.matches("\\W\\S");
        boolean reservedPrefix = newPrefix.contains("@") || newPrefix.contains("/") || newPrefix.contains(",");

        return (nonAlphanumericPrefix1 || nonAlphanumericPrefix2) && !reservedPrefix;
    }

    @Override
    public String getName() {
        return "setconfig";
    }

    @Override
    public String getDescription() {
        return "Use this command to set the configuration used for your server";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData configName = new OptionData(OptionType.STRING, CONFIG_NAME, "What config to adjust for the server", true);
        OptionData configValue = new OptionData(OptionType.STRING, CONFIG_VALUE, "Value for the config you want to adjust", true);
        Arrays.stream(values()).forEach(conf -> configName.addChoice(conf.getConfigValue(), conf.getConfigValue()));
        return List.of(configName, configValue);
    }
}
