package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static toby.jpa.dto.ConfigDto.Configurations.*;

public class SetConfigCommand implements IModerationCommand {

    private final IConfigService configService;

    public SetConfigCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getEvent().getHook(), deleteDelay);
        SlashCommandInteractionEvent event = ctx.getEvent();
        List<OptionMapping> args = event.getOptions();
        final Member member = ctx.getMember();

        if (!member.isOwner()) {
            event.reply("This is currently reserved for the owner of the server only, this may change in future").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        if (args.isEmpty()) {
            event.reply(getDescription()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay);
    }


    private void validateArgumentsAndUpdateConfigs(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String configNameString = event.getOption("Config Name").getAsString().toUpperCase();

        if (configNameString.isEmpty()) {
            event.reply(getDescription()).queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        ConfigDto.Configurations configName = valueOf(configNameString);

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
        String newDefaultDelay = event.getOption("Config Value").getAsString();
        if (!newDefaultDelay.matches("\\d+")) {
            event.reply("Value given for default delete message delay for TobyBot music messages was not valid (a whole number representing seconds).").queue(message -> ICommand.deleteAfter(message, deleteDelay));
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
        event.replyFormat("Set default delete message delay for TobyBot music messages to '%s' seconds", newDefaultDelay).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    private void setVolume(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String newDefaultVolume = event.getOption("Config Value").getAsString();
        String volumePropertyName = VOLUME.getConfigValue();
        ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        ConfigDto newConfigDto = new ConfigDto(volumePropertyName, newDefaultVolume, event.getGuild().getId());
        if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
            configService.updateConfig(newConfigDto);
        } else {
            configService.createNewConfig(newConfigDto);
        }
        event.replyFormat("Set default volume to '%s'", newDefaultVolume).queue(message -> ICommand.deleteAfter(message, deleteDelay));
    }

    private void setMove(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String newDefaultMoveChannel = event.getOption("Config Value").getAsString();
        boolean newDefaultVoiceChannelExists = !event.getGuild().getVoiceChannelsByName(newDefaultMoveChannel, true).isEmpty();
        if (newDefaultVoiceChannelExists) {
            String movePropertyName = MOVE.getConfigValue();
            ConfigDto databaseConfig = configService.getConfigByName(movePropertyName, event.getGuild().getId());
            ConfigDto newConfigDto = new ConfigDto(movePropertyName, newDefaultMoveChannel, event.getGuild().getId());
            if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                configService.updateConfig(newConfigDto);
            } else {
                configService.createNewConfig(newConfigDto);
            }
            event.replyFormat("Set default move channel to '%s'", newDefaultMoveChannel).queue(message -> ICommand.deleteAfter(message, deleteDelay));

        }
    }

    private void setPrefix(SlashCommandInteractionEvent event, Integer deleteDelay) {
        String newPrefix = event.getOption("Config Value").getAsString();
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
            event.replyFormat("Set prefix to '%s'", newPrefix).queue(message -> ICommand.deleteAfter(message, deleteDelay));

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
        OptionData configName = new OptionData(OptionType.STRING, "Config Name", "What config to adjust for the server", true);
        OptionData configValue = new OptionData(OptionType.STRING, "Config Value", "Value for the config you want to adjust", true);
        Arrays.stream(values()).forEach(conf -> configName.addChoice(conf.getConfigValue(), conf.getConfigValue()));
        return List.of(configName, configValue);
    }
}
