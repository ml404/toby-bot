package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.*;
import java.util.stream.Collectors;

public class TeamCommand implements IMiscCommand {


    private final String TEAM_MEMBERS = "members";
    private final String TEAM_SIZE = "size";
    private final String CLEANUP = "cleanup";

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        cleanupTemporaryChannels(event.getGuild().getChannels());
        ICommand.deleteAfter(event.getHook(), deleteDelay);
        List<OptionMapping> args = event.getOptions();
        if (Optional.ofNullable(event.getOption(CLEANUP)).map(OptionMapping::getAsBoolean).orElse(false)) {
            return;
        }
        if (args.isEmpty()) {
            event.getHook().sendMessage(getDescription()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
            return;
        }
        //Shuffle gives an NPE with default return of message.getMentionedMembers()
        List<Member> mentionedMembers = Optional.ofNullable(event.getOption(TEAM_MEMBERS)).map(OptionMapping::getMentions).map(Mentions::getMembers).orElse(Collections.emptyList());
        int listsToInitialise = Optional.ofNullable(event.getOption(TEAM_SIZE)).map(OptionMapping::getAsInt).orElse(2);
        listsToInitialise = Math.min(listsToInitialise, mentionedMembers.size());
        List<List<Member>> teams = split(mentionedMembers, listsToInitialise);

        StringBuilder sb = new StringBuilder();
        Guild guild = event.getGuild();
        for (int i = 0; i < teams.size(); i++) {
            String teamName = String.format("Team %d", i + 1);
            sb.append(String.format("**%s**: %s \n", teamName, teams.get(i).stream().map(Member::getEffectiveName).collect(Collectors.joining(", "))));
            ChannelAction<VoiceChannel> voiceChannel = guild.createVoiceChannel(teamName);
            VoiceChannel createdVoiceChannel = voiceChannel.setBitrate(guild.getMaxBitrate()).complete();
            teams.get(i).forEach(target -> guild.moveVoiceMember(target, createdVoiceChannel)
                    .queue(
                            (__) -> event.getHook().sendMessageFormat("Moved %s to '%s'", target.getEffectiveName(), createdVoiceChannel.getName()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay)),
                            (error) -> event.getHook().sendMessageFormat("Could not move '%s'", error.getMessage()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay))
                    ));
        }
        event.getHook().sendMessage(sb.toString()).queue(message1 -> ICommand.deleteAfter(message1, deleteDelay));
    }

    private void cleanupTemporaryChannels(List<GuildChannel> channels) {
        channels.stream()
                .filter(guildChannel -> guildChannel.getName().matches("(?i)team\\s[0-9]+"))
                .forEach(guildChannel -> guildChannel.delete().queue());
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
    public String getDescription() {
        return "Return X teams from a list of tagged users.";
    }

    @Override
    public List<OptionData> getOptionData() {
        return List.of(
                new OptionData(OptionType.STRING, TEAM_MEMBERS, "Which discord users would you like to split into the teams?", true),
                new OptionData(OptionType.INTEGER, TEAM_SIZE, "Number of teams you want to split members into (defaults to 2)"),
                new OptionData(OptionType.BOOLEAN, CLEANUP, "Do you want to perform cleanup to reset the temporary channels in the guild?")
        );

    }
}
