package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.Arrays;
import java.util.List;


public class UserInfoCommand implements IMiscCommand {


    private IUserService userService;

    public UserInfoCommand(IUserService userService) {

        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();

        printUserInfo(channel, message, requestingUserDto, deleteDelay);
    }

    private void printUserInfo(TextChannel channel, Message message, UserDto requestingUserDto, Integer deleteDelay) {
        if (message.getMentions().getMembers().isEmpty()) {
            if (requestingUserDto != null) {
                channel.sendMessage(String.format("Here are your permissions: '%s'.", requestingUserDto)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                MusicDto musicDto = requestingUserDto.getMusicDto();
                if (musicDto != null) {
                    if (musicDto.getFileName() == null || musicDto.getFileName().isBlank()) {
                        channel.sendMessage("There is no intro music file associated with your user.").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));

                    } else if (musicDto.getFileName() != null) {
                        channel.sendMessage(String.format("Your intro song is currently set as: '%s'.", musicDto.getFileName())).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                    }
                } else
                    channel.sendMessage("I was unable to retrieve your music file.").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            }
        } else {
            if (requestingUserDto.isSuperUser()) {
                message.getMentions().getMembers().forEach(member -> {
                    UserDto mentionedUser = userService.getUserById(member.getIdLong(), member.getGuild().getIdLong());
                    channel.sendMessageFormat("Here are the permissions for '%s': '%s'.", member.getEffectiveName(), mentionedUser).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                    MusicDto musicDto = mentionedUser.getMusicDto();
                    if (musicDto != null) {
                        if (musicDto.getFileName() == null || musicDto.getFileName().isBlank()) {
                            channel.sendMessageFormat("There is no intro music file associated with '%s'.", member.getEffectiveName()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));

                        } else if (musicDto.getFileName() != null) {
                            channel.sendMessage(String.format("Their intro song is currently set as: '%s'.", musicDto.getFileName())).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                        }
                    } else
                        channel.sendMessageFormat("I was unable to retrieve an associated music file for '%s'.", member.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                });
            }
            else channel.sendMessage("You do not have permission to view user permissions, if this is a mistake talk to the server owner").queue();
        }
    }

    @Override
    public String getName() {
        return "userinfo";
    }

    @Override
    public String getHelp(String prefix) {
        return "Let me tell you about your permissions.\n" +
                String.format("Usage: `%suserinfo`", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("getuser", "info", "permissions", "permission", "perm");
    }
}