package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Map.Entry.comparingByValue;
import static java.util.stream.Collectors.toMap;

public class SocialCreditCommand implements IModerationCommand {

    private final IUserService userService;
    private final String LEADERBOARD = "leaderboard";
    private final String USERS = "users";
    private final String SOCIAL_CREDIT = "credit";

    public SocialCreditCommand(IUserService userService) {
        this.userService = userService;
    }

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        ICommand.deleteAfter(event.getHook(), deleteDelay);
        List <OptionMapping> args = event.getOptions();
        final Member member = ctx.getMember();
        if(!event.getGuild().isLoaded()) event.getGuild().loadMembers();
        if (event.getOption(LEADERBOARD).getAsBoolean()) {
            Map<Long, Long> discordSocialCreditMap = new HashMap<>();
            userService.listGuildUsers(event.getGuild().getIdLong()).forEach(userDto -> {
                Long socialCredit = userDto.getSocialCredit() == null ? 0L : userDto.getSocialCredit();
                discordSocialCreditMap.put(userDto.getDiscordId(), socialCredit);
            });
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("**Social Credit Leaderboard**\n");
            stringBuilder.append("**-----------------------------**\n");
            LinkedHashMap<Long, Long> sortedMap = discordSocialCreditMap.entrySet()
                    .stream()
                    .sorted(comparingByValue(Comparator.reverseOrder()))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
            AtomicInteger position = new AtomicInteger();
            sortedMap.forEach((k, v) -> {
                position.getAndIncrement();
                event.getGuild()
                        .getMembers()
                        .stream()
                        .filter(member1 -> member1.getIdLong() == k)
                        .findFirst()
                        .ifPresent(memberById -> stringBuilder.append(String.format("#%s: %s - score: %d\n", position, memberById.getEffectiveName(), v)));
            });
            event.replyFormat(stringBuilder.toString()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        } else {
            List<Member> mentionedMembers = event.getOption(USERS).getMentions().getMembers();
            if (mentionedMembers.isEmpty()) {
                listSocialCreditScore(requestingUserDto, member.getEffectiveName(), deleteDelay, event);
            } else
                mentionedMembers.forEach(targetMember ->
                {
                    UserDto targetUserDto = userService.getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());

                    if (event.getOptions().stream().noneMatch(optionMapping -> optionMapping.getAsMember().getUser().getAsMention().isEmpty())) {
                        listSocialCreditScore(targetUserDto, targetMember.getEffectiveName(), deleteDelay, event);
                    } else {
                        //Check to see if the database contained an entry for the user we have made a request against
                        if (targetUserDto != null) {
                            boolean isSameGuild = requestingUserDto.getGuildId().equals(targetUserDto.getGuildId());
                            boolean requesterCanAdjustPermissions = member.isOwner();
                            if (requesterCanAdjustPermissions && isSameGuild) {
                                long socialCreditAdjustment = event.getOption(SOCIAL_CREDIT).getAsLong();
                                UserDto updatedUser = validateArgumentsAndAdjustSocialCredit(targetUserDto, event, socialCreditAdjustment, ctx.getMember().isOwner(), deleteDelay);
                                event.replyFormat("Updated user %s's social credit by %d. New score is: %d", targetMember.getEffectiveName(), socialCreditAdjustment, updatedUser.getSocialCredit()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                            } else
                                event.replyFormat("User '%s' is not allowed to adjust the social credit of user '%s'.", member.getNickname(), targetMember.getNickname()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
                        }
                    }
                });
        }

    }

    private void listSocialCreditScore(UserDto userDto, String mentionedName, Integer deleteDelay, SlashCommandInteractionEvent event) {
        Long socialCredit = userDto.getSocialCredit() == null ? 0L : userDto.getSocialCredit();
        event.replyFormat("%s's social credit is: %d", mentionedName, socialCredit).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
    }

    private UserDto validateArgumentsAndAdjustSocialCredit(UserDto targetUserDto, SlashCommandInteractionEvent event, Long socialCreditScore, boolean isOwner, Integer deleteDelay) {
        if (isOwner) {
            Long socialCredit = targetUserDto.getSocialCredit() == null ? 0L : targetUserDto.getSocialCredit();
            targetUserDto.setSocialCredit(socialCredit + socialCreditScore);
            userService.updateUser(targetUserDto);
            return targetUserDto;
        } else
            sendErrorMessage(event, deleteDelay);
        return targetUserDto;
    }


    @Override
    public String getName() {
        return "socialcredit";
    }

    @Override
    public String getDescription() {
        return "Use this command to adjust the mentioned user's social credit.";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData leaderboard = new OptionData(OptionType.BOOLEAN, LEADERBOARD, "Show the leaderboard");
        OptionData users = new OptionData(OptionType.STRING, USERS, "User(s) to adjust the social credit value. Without a value will display their social credit amount");
        OptionData creditAmount = new OptionData(OptionType.INTEGER, SOCIAL_CREDIT, "Score to add or deduct from mentioned user's social credit");
        return List.of(users, creditAmount, leaderboard);
    }
}
