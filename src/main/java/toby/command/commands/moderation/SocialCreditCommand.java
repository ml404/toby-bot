package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import toby.command.CommandContext;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Map.Entry.comparingByValue;
import static java.util.stream.Collectors.toMap;
import static toby.command.ICommand.invokeDeleteOnMessageResponse;

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
        event.deferReply().queue();
        final Member member = ctx.getMember();
        if (!event.getGuild().isLoaded()) event.getGuild().loadMembers();
        if (Optional.ofNullable(event.getOption(LEADERBOARD)).map(OptionMapping::getAsBoolean).orElse(false)) {
            createAndPrintLeaderboard(event, deleteDelay);
            return;
        }
        calculateAndUpdateSocialCredit(ctx, event, requestingUserDto, member, deleteDelay);

    }

    private void calculateAndUpdateSocialCredit(CommandContext ctx, SlashCommandInteractionEvent event, UserDto requestingUserDto, Member requestingMember, Integer deleteDelay) {
        Optional<User> optionalUser = Optional.ofNullable(event.getOption(USERS)).map(OptionMapping::getAsUser);
        if (optionalUser.isEmpty()) {
            listSocialCreditScore(event, requestingUserDto, requestingMember.getEffectiveName(), deleteDelay);
            return;
        }
        User user = optionalUser.get();
        //Check to see if the database contained an entry for the user we have made a request against
        UserDto targetUserDto = userService.getUserById(user.getIdLong(), requestingUserDto.getGuildId());
        if (targetUserDto != null) {
            boolean isSameGuild = requestingUserDto.getGuildId().equals(targetUserDto.getGuildId());
            if (requestingMember.isOwner() && isSameGuild) {
                Optional<Long> scOptional = Optional.ofNullable(event.getOption(SOCIAL_CREDIT)).map(OptionMapping::getAsLong);
                if (scOptional.isPresent()) {
                    UserDto updatedUser = updateUserSocialCredit(targetUserDto, scOptional.get());
                    event.getHook().sendMessageFormat("Updated user %s's social credit by %d. New score is: %d", user.getEffectiveName(), scOptional.get(), updatedUser.getSocialCredit()).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
                } else {
                    listSocialCreditScore(event, targetUserDto, user.getEffectiveName(), deleteDelay);
                }
            } else
                event.getHook().sendMessageFormat("User '%s' is not allowed to adjust the social credit of user '%s'.", requestingMember.getEffectiveName(), user.getEffectiveName()).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
        }
    }


    private void createAndPrintLeaderboard(SlashCommandInteractionEvent event, Integer deleteDelay) {
        Map<Long, Long> discordSocialCreditMap = new HashMap<>();
        Guild guild = event.getGuild();
        userService.listGuildUsers(guild.getIdLong()).forEach(userDto -> {
            Long socialCredit = userDto.getSocialCredit() == null ? 0L : userDto.getSocialCredit();
            discordSocialCreditMap.put(userDto.getDiscordId(), socialCredit);
        });
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("**Social Credit Leaderboard**\n");
        stringBuilder.append("**-----------------------------**\n");
        LinkedHashMap<Long, Long> sortedMap = discordSocialCreditMap
                .entrySet()
                .stream()
                .sorted(comparingByValue(Comparator.reverseOrder()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
        AtomicInteger position = new AtomicInteger();
        sortedMap.forEach((k, v) -> {
            position.getAndIncrement();
            guild.getMembers()
                    .stream()
                    .filter(member1 -> member1.getIdLong() == k)
                    .findFirst()
                    .ifPresent(memberById -> stringBuilder.append(String.format("#%s: %s - score: %d\n", position, memberById.getEffectiveName(), v)));
        });
        event.getHook().sendMessageFormat(stringBuilder.toString()).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }

    private void listSocialCreditScore(SlashCommandInteractionEvent event, UserDto userDto, String mentionedName, Integer deleteDelay) {
        Long socialCredit = userDto.getSocialCredit() == null ? 0L : userDto.getSocialCredit();
        event.getHook().sendMessageFormat("%s's social credit is: %d", mentionedName, socialCredit).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay));
    }

    private UserDto updateUserSocialCredit(UserDto targetUserDto, Long socialCreditScore) {
        Long socialCredit = targetUserDto.getSocialCredit() == null ? 0L : targetUserDto.getSocialCredit();
        targetUserDto.setSocialCredit(socialCredit + socialCreditScore);
        userService.updateUser(targetUserDto);
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
        OptionData users = new OptionData(OptionType.USER, USERS, "User(s) to adjust the social credit value. Without a value will display their social credit amount");
        OptionData creditAmount = new OptionData(OptionType.NUMBER, SOCIAL_CREDIT, "Score to add or deduct from mentioned user's social credit");
        return List.of(users, creditAmount, leaderboard);
    }
}
