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

        List<Member> mentionedMembers = channelAndArgumentValidation(requestingUserDto, channel, message, member, deleteDelay);
        if (mentionedMembers == null) {
            listSocialCreditScore(requestingUserDto, deleteDelay, channel);
        } else
            mentionedMembers.forEach(targetMember ->
            {
                UserDto targetUserDto = userService.getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
                if (args.subList(0, args.size()).stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).count() == 0) {
                    listSocialCreditScore(requestingUserDto, deleteDelay, channel);
                } else {
                    //Check to see if the database contained an entry for the user we have made a request against
                    if (targetUserDto != null) {
                        boolean isSameGuild = requestingUserDto.getGuildId().equals(targetUserDto.getGuildId());
                        boolean requesterCanAdjustPermissions = member.isOwner();
                        if (requesterCanAdjustPermissions && isSameGuild) {
                            String socialCreditString = args.subList(0, args.size()).stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).findFirst().get();
                            UserDto updatedUser = validateArgumentsAndAdjustSocialCredit(ctx, targetUserDto, channel, Long.valueOf(socialCreditString), ctx.getMember().isOwner(), deleteDelay);
                            channel.sendMessageFormat("Updated user %s's social credit by %s. New score is: %d", targetMember.getEffectiveName(), socialCreditString, updatedUser.getSocialCredit()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                        } else
                            channel.sendMessageFormat("User '%s' is not allowed to adjust the social credit of user '%s'.", member.getNickname(), targetMember.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                    }
                }
            });


    }

    private void listSocialCreditScore(UserDto requestingUserDto, Integer deleteDelay, TextChannel channel) {
        Long socialCredit = requestingUserDto.getSocialCredit() == null ? 0L : requestingUserDto.getSocialCredit();
        channel.sendMessageFormat("Your social credit is: %d", socialCredit).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
    }

    private UserDto validateArgumentsAndAdjustSocialCredit(CommandContext ctx, UserDto targetUserDto, TextChannel channel, Long socialCreditScore, boolean isOwner, Integer deleteDelay) {
        if (isOwner) {
            Long socialCredit = targetUserDto.getSocialCredit() == null ? 0L : targetUserDto.getSocialCredit();
            targetUserDto.setSocialCredit(socialCredit + socialCreditScore);
            userService.updateUser(targetUserDto);
            return targetUserDto;
        } else
            sendErrorMessage(ctx, channel, deleteDelay);
        return targetUserDto;
    }


    @Nullable
    private List<Member> channelAndArgumentValidation(UserDto requestingUserDto, TextChannel channel, Message message, Member member, int deleteDelay) {
        if (!member.isOwner() && !requestingUserDto.isSuperUser()) {
            channel.sendMessage("This command is reserved for the owner of the server only, this may change in the future").queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return null;
        }
        return message.getMentionedMembers();
    }

    @Override
    public String getName() {
        return "socialcredit";
    }

    @Override
    public String getHelp(String prefix) {
        return "Use this command to adjust the mentioned user's social credit.\n" +
                String.format("Usage: `%ssocialcredit <@user> +/-100` to adjust the mentioned users social credit \n", prefix) +
                String.format("Usage: `%ssocialcredit <@user>` to show the tagged user's social credit \n", prefix) +
                String.format("Usage: `%ssocialcredit` to show your social credit \n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("credit", "sc");
    }

}
