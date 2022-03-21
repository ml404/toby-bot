package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.*;
import java.util.stream.Collectors;

public class TeamCommand implements IMiscCommand {


    @Override
    public void handle(CommandContext ctx, String prefix, UserDto requestingUserDto, Integer deleteDelay) {

        final TextChannel channel = ctx.getChannel();
        final Message message = ctx.getMessage();
        ICommand.deleteAfter(message, deleteDelay);
        if (ctx.getArgs().isEmpty()) {
            channel.sendMessage(getHelp(prefix)).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
        }
        //Shuffle gives an NPE with default return of message.getMentionedMembers()
        List<Member> mentionedMembers = new ArrayList<>(message.getMentionedMembers());
        Optional<String> teamOptional = ctx.getArgs().stream().filter(s -> !s.matches(Message.MentionType.USER.getPattern().pattern())).filter(s -> Integer.parseInt(s) > 0).findFirst();
        int defaultNumberOfTeams = 2;
        int listsToInitialise = teamOptional.map(Integer::parseInt).orElse(defaultNumberOfTeams);
        listsToInitialise = Math.min(listsToInitialise, mentionedMembers.size());
        List<List<Member>> teams = split(mentionedMembers, listsToInitialise);

        StringBuilder sb = new StringBuilder();
        Guild guild = ctx.getGuild();
        for (int i = 0; i < teams.size(); i++) {
            String teamName = String.format("Team %d", i + 1);
            sb.append(String.format("**%s**: %s \n", teamName, teams.get(i).stream().map(Member::getEffectiveName).collect(Collectors.joining(", "))));
            ChannelAction<VoiceChannel> voiceChannel = guild.createVoiceChannel(teamName);
            VoiceChannel createdVoiceChannel = voiceChannel.setBitrate(guild.getMaxBitrate()).complete();
            teams.get(i).forEach(target -> guild.moveVoiceMember(target, createdVoiceChannel)
                    .queue(
                            (__) -> channel.sendMessageFormat("Moved %s to '%s'", target.getEffectiveName(), createdVoiceChannel.getName()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay)),
                            (error) -> channel.sendMessageFormat("Could not move '%s'", error.getMessage()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay))
                    ));
        }
        channel.sendMessage(sb).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
    }


    public static List<List<Member>> split(List<Member> list, int splitSize) {
        List<List<Member>> result = new ArrayList<>();
        Collections.shuffle(list);
        int numberOfMembersTagged = list.size();
        for (int i = 0; i < splitSize; i++) {
            int sizeOfTeams = (numberOfMembersTagged) / splitSize;
            int fromIndex = i * sizeOfTeams;
            int toIndex = (i + 1) * sizeOfTeams;
            result.add(new ArrayList<>(list.subList(fromIndex, toIndex)));
        }

        return result;
    }

    @Override
    public String getName() {
        return "team";
    }

    @Override
    public String getHelp(String prefix) {
        return "Return X teams from a list of tagged users. \n" +
                String.format("Usage: %steam @person1 @person2 \n", prefix) +
                String.format("%steam @person1 @person2 $numberOfTeams (defaults to 2) \n", prefix) +
                String.format("Aliases are: '%s'", String.join(",", getAliases()));
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("teams");
    }
}
