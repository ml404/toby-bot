package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.List;

public class SetPrefixCommand implements IModerationCommand {

    private final IConfigService configService;

    public SetPrefixCommand(IConfigService configService) {
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
        String newPrefix = args.get(0);
        newPrefix = newPrefix.length() > 2 ? newPrefix.substring(0, 2) : newPrefix;

        if (prefixValidation(newPrefix)) {
            ConfigDto databaseConfig = configService.getConfigByName("PREFIX", ctx.getGuild().getId());
            ConfigDto newConfigDto = new ConfigDto("PREFIX", newPrefix, ctx.getGuild().getId());

            if (databaseConfig.getGuildId().equals(newConfigDto.getGuildId())) {
                configService.updateConfig(newConfigDto);
            } else {
                configService.createNewConfig(newConfigDto);
            }
            channel.sendMessageFormat("Set prefix to '%s'", newPrefix).queue();
        } else
            channel.sendMessage(getHelp(prefix)).queue();

    }

    @Override
    public String getName() {
        return "setprefix";
    }

    @Override
    public String getHelp(String prefix) {
        return "Use this command to set the prefix used in commands for your server\n" +
                String.format("Usage: `%ssetPrefix ?` \n", prefix) +
                "Subsequent commands would then be called with '?' like so: `?roll 6` \n" +
                "Please use up to 2 non alphanumeric prefixes only excluding '@' and '/'";
    }

    private boolean prefixValidation(String newPrefix) {
        boolean nonAlphanumericPrefix1 = newPrefix.matches("[^a-zA-Z\\d*\\s]");
        boolean nonAlphanumericPrefix2 = newPrefix.matches("\\W\\S");
        boolean reservedPrefix = newPrefix.contains("@") || newPrefix.contains("/");

        return (nonAlphanumericPrefix1 || nonAlphanumericPrefix2) && !reservedPrefix;
    }

}
