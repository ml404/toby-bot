package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import org.jetbrains.annotations.Nullable;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static toby.helpers.UserDtoHelper.userAdjustmentValidation;

public class AdjustUserCommand implements IModerationCommand {

    private final IUserService userService;

    public AdjustUserCommand(IUserService userService) {
        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {
        ICommand.deleteAfter(ctx.getMessage(), deleteDelay);
        List<String> args = ctx.getArgs();
        TextChannel channel = ctx.getChannel();
        Message message = ctx.getMessage();
        final Member member = ctx.getMember();

        List<Member> mentionedMembers = channelAndArgumentValidation(prefix, requestingUserDto, args, channel, message, member, deleteDelay);
        if (mentionedMembers == null) return;

        mentionedMembers.forEach(targetMember -> {
            UserDto targetUserDto = userService.getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
            //Check to see if the database contained an entry for the user we have made a request against
            if (targetUserDto != null) {
                boolean isSameGuild = requestingUserDto.getGuildId().equals(targetUserDto.getGuildId());
                boolean requesterCanAdjustPermissions = userAdjustmentValidation(requestingUserDto, targetUserDto) || member.isOwner();
                if (requesterCanAdjustPermissions && isSameGuild) {
                    validateArgumentsAndUpdateUser(channel, targetUserDto, args, member.isOwner(), deleteDelay);
                    channel.sendMessageFormat("Updated user %s's permissions", targetMember.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                } else
                    channel.sendMessageFormat("User '%s' is not allowed to adjust the permissions of user '%s'.", member.getNickname(), targetMember.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));

            } else {
                createNewUser(channel, targetMember, deleteDelay);
            }
        });


    }

    private void validateArgumentsAndUpdateUser(TextChannel channel, UserDto targetUserDto, List<String> args, Boolean isOwner, int deleteDelay) {
        List<String> valuesToAdjust = args.stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).collect(Collectors.toList());
        Map<String, Boolean> permissionMap = valuesToAdjust.stream()
                .map(s -> s.split("=", 2))
                .filter(strings -> UserDto.Permissions.isValidEnum(strings[0].toUpperCase()) && (strings[1] != null && (strings[1].equalsIgnoreCase("false") || strings[1].equalsIgnoreCase("true"))))
                .collect(Collectors.toMap(s -> s[0], s -> Boolean.valueOf(s[1])));

        if (permissionMap.isEmpty()) {
            channel.sendMessage("You did not mention a valid permission to update").queue(message -> ICommand.deleteAfter(message, deleteDelay));
            return;
        }

        if (permissionMap.containsKey(UserDto.Permissions.MUSIC.name()))
            targetUserDto.setMusicPermission(permissionMap.get(UserDto.Permissions.MUSIC.name()));
        if (permissionMap.containsKey(UserDto.Permissions.DIG.name()))
            targetUserDto.setDigPermission(permissionMap.get(UserDto.Permissions.DIG.name()));
        if (permissionMap.containsKey(UserDto.Permissions.MEME.name()))
            targetUserDto.setMemePermission(permissionMap.get(UserDto.Permissions.MEME.name()));
        if (permissionMap.containsKey(UserDto.Permissions.SUPERUSER.name()) && isOwner)
            targetUserDto.setSuperUser(permissionMap.get(UserDto.Permissions.SUPERUSER.name()));

        userService.updateUser(targetUserDto);
    }

    private void createNewUser(TextChannel channel, Member targetMember, int deleteDelay) {
        //Database did not contain an entry for the user we have made a request against, so make one.
        UserDto newDto = new UserDto();
        newDto.setDiscordId(targetMember.getIdLong());
        newDto.setGuildId(targetMember.getGuild().getIdLong());
        userService.createNewUser(newDto);
        channel.sendMessageFormat("User %s's permissions did not exist in this server's database, they have now been created", targetMember.getNickname()).queue(message -> ICommand.deleteAfter(message,deleteDelay));
    }

    @Nullable
    private List<Member> channelAndArgumentValidation(String prefix, UserDto requestingUserDto, List<String> args, TextChannel channel, Message message, Member member, int deleteDelay) {
        if (!member.isOwner() && !requestingUserDto.isSuperUser()) {
            channel.sendMessage("This command is reserved for the owner of the server and users marked as super users only, this may change in the future").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }

        if (args.stream().allMatch(s -> s.matches(Message.MentionType.USER.getPattern().pattern()))) {
            channel.sendMessage(getHelp(prefix)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }

        List<Member> mentionedMembers = message.getMentions().getMembers();
        if (mentionedMembers.isEmpty()) {
            channel.sendMessage("You must mention 1 or more Users to adjust permissions of").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }
        return mentionedMembers;
    }

    @Override
    public String getName() {
        return "adjustuser";
    }

    @Override
    public String getHelp(String prefix) {
        return "Use this command to adjust the mentioned user's permissions to use TobyBot commands for your server\n" +
                String.format("Usage: `%sadjustuser <@user> permission1=true/false permission2=true/false`... \n", prefix) +
                String.format("Adjustable values are as follows: %s \n", Arrays.stream(UserDto.Permissions.values()).map(Enum::name).collect(Collectors.joining(","))) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("setuser", "user");
    }
}
