package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class SetConfigCommand implements IModerationCommand {

    private final IConfigService configService;

    public SetConfigCommand(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto) {

        List<String> args = ctx.getArgs();
        TextChannel channel = ctx.getChannel();
        final Member member = ctx.getMember();

        if (!member.isOwner()) {
            channel.sendMessage("This is currently reserved for the owner of the server only, this may change in future").queue();
            return;
        }

        if (args.isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue();
            return;
        }
        validateArgumentsAndUpdateConfigs(ctx, channel, prefix, args);
    }


    private void validateArgumentsAndUpdateConfigs(CommandContext ctx, TextChannel channel, String prefix, List<String> args) {
        String valuesToAdjust = args.stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).collect(Collectors.joining(" "));


        Map<String, String> configMap = Arrays.stream(valuesToAdjust.split(","))
                .map(s -> s.split("=", 2))
                .filter(strings -> ConfigDto.Configurations.isValidEnum(strings[0].toUpperCase().trim()) && (strings[1] != null))
                .collect(Collectors.toMap(s -> s[0].toUpperCase().trim(), s -> s[1].trim()));

        if (configMap.isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue();
            return;
        }

        if (configMap.containsKey(ConfigDto.Configurations.PREFIX.name())) {
            String newPrefix = configMap.get(ConfigDto.Configurations.PREFIX.name());
            newPrefix = newPrefix.length() > 2 ? newPrefix.substring(0, 2) : newPrefix;
            if (prefixValidation(newPrefix)) {
                String prefixPropertyName = ConfigDto.Configurations.PREFIX.getConfigValue();
                ConfigDto databaseConfig = configService.getConfigByName(prefixPropertyName, ctx.getGuild().getId());
                ConfigDto newConfigDto = new ConfigDto(prefixPropertyName, newPrefix, ctx.getGuild().getId());

                if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                    configService.updateConfig(newConfigDto);
                } else {
                    configService.createNewConfig(newConfigDto);
                }
                channel.sendMessageFormat("Set prefix to '%s'", newPrefix).queue();

            }
        }
        if (configMap.containsKey(ConfigDto.Configurations.MOVE.name())) {
            String newDefaultMoveChannel = configMap.get(ConfigDto.Configurations.MOVE.name());
            boolean newDefaultVoiceChannelExists = !ctx.getGuild().getVoiceChannelsByName(newDefaultMoveChannel, true).isEmpty();
            if (newDefaultVoiceChannelExists) {
                String movePropertyName = ConfigDto.Configurations.MOVE.getConfigValue();
                ConfigDto databaseConfig = configService.getConfigByName(movePropertyName, ctx.getGuild().getId());
                ConfigDto newConfigDto = new ConfigDto(movePropertyName, newDefaultMoveChannel, ctx.getGuild().getId());
                if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                    configService.updateConfig(newConfigDto);
                } else {
                    configService.createNewConfig(newConfigDto);
                }
                channel.sendMessageFormat("Set default move channel to '%s'", newDefaultMoveChannel).queue();

            }
        }

        if (configMap.containsKey(ConfigDto.Configurations.VOLUME.name())) {
            String newDefaultVolume = configMap.get(ConfigDto.Configurations.VOLUME.name());
            String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
            ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, ctx.getGuild().getId());
            ConfigDto newConfigDto = new ConfigDto(volumePropertyName, newDefaultVolume, ctx.getGuild().getId());
            if (databaseConfig != null && Objects.equals(databaseConfig.getGuildId(), newConfigDto.getGuildId())) {
                configService.updateConfig(newConfigDto);
            } else {
                configService.createNewConfig(newConfigDto);
            }
            channel.sendMessageFormat("Set default volume to '%s'", newDefaultVolume).queue();


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
    public String getHelp(String prefix) {
        return "Use this command to set the configuration used for your server\n" +
                String.format("Usage: `%ssetConfig prefix=? move=i have a bad opinion volume=10` \n", prefix) +
                String.format("Aliases are: %s \n", String.join(",", getAliases())) +
                String.format("Adjustable values are as follows: %s", Arrays.stream(ConfigDto.Configurations.values()).map(Enum::name).collect(Collectors.joining(",")));
    }

    @Override
    public List<String> getAliases(){
        return Arrays.asList("conf", "config");
    }
}
