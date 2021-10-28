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

public class SocialCreditCommand implements IModerationCommand {

    private final IUserService userService;

    public SocialCreditCommand(IUserService userService) {
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
            if (args.isEmpty()) {
                channel.sendMessage(getHelp(prefix)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            } else {
                UserDto targetUserDto = userService.getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
                //Check to see if the database contained an entry for the user we have made a request against
                if (targetUserDto != null) {
                    boolean isSameGuild = requestingUserDto.getGuildId().equals(targetUserDto.getGuildId());
                    boolean requesterCanAdjustPermissions = member.isOwner();
                    if (requesterCanAdjustPermissions && isSameGuild) {
                        String socialCreditString = args.subList(0, args.size()).stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).findFirst().get();
                        UserDto updatedUser = validateArgumentsAndAdjustSocialCredit(ctx, targetUserDto, channel, Long.valueOf(socialCreditString), ctx.getMember().isOwner(), deleteDelay);
                        channel.sendMessageFormat("Updated user %s's social credit by %s. New score is: %d", targetMember.getNickname(), socialCreditString, updatedUser.getSocialCredit()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                    } else
                        channel.sendMessageFormat("User '%s' is not allowed to adjust the social credit of user '%s'.", member.getNickname(), targetMember.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                }
            }
        });


    }

    private UserDto validateArgumentsAndAdjustSocialCredit(CommandContext ctx, UserDto targetUserDto, TextChannel channel, Long socialCreditScore, boolean isOwner, Integer deleteDelay) {
        if (isOwner) {
            Long socialCredit = targetUserDto.getSocialCredit() == null ? 0L : targetUserDto.getSocialCredit();;
            targetUserDto.setSocialCredit(socialCredit + socialCreditScore);
            userService.updateUser(targetUserDto);
            return targetUserDto;
        } else
            sendErrorMessage(ctx, channel, deleteDelay);
        return targetUserDto;
    }


    @Nullable
    private List<Member> channelAndArgumentValidation(String prefix, UserDto
            requestingUserDto, List<String> args, TextChannel channel, Message message, Member member, int deleteDelay) {
        if (!member.isOwner() && !requestingUserDto.isSuperUser()) {
            channel.sendMessage("This command is reserved for the owner of the server and users marked as super users only, this may change in the future").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }

        if (args.stream().allMatch(s -> s.matches(Message.MentionType.USER.getPattern().pattern()))) {
            channel.sendMessage(getHelp(prefix)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }

        List<Member> mentionedMembers = message.getMentionedMembers();
        if (mentionedMembers.isEmpty()) {
            channel.sendMessage("You must mention 1 or more Users to adjust credit of").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }
        return mentionedMembers;
    }

    @Override
    public String getName() {
        return "socialcredit";
    }

    @Override
    public String getHelp(String prefix) {
        return "Use this command to adjust the mentioned user's social credit.\n" +
                String.format("Usage: `%ssocialcredit <@user> +/-100`... \n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("credit", "sc");
    }

}
