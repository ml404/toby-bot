package toby.command.commands.dnd;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.attribute.IMemberContainer;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.NotNull;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.helpers.DnDHelper;
import toby.jpa.dto.UserDto;

import java.util.*;

import static toby.helpers.DnDHelper.rollInitiativeForMembers;
import static toby.helpers.DnDHelper.rollInitiativeForString;

public class InitiativeCommand implements IDnDCommand {

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        Optional<String> namesOptional = Optional.ofNullable(event.getOption("names")).map(OptionMapping::getAsString);
        Member dm = Optional.ofNullable(event.getOption("dm")).map(OptionMapping::getAsMember).orElse(ctx.getMember());
        Optional<GuildVoiceState> voiceState = Optional.ofNullable(member.getVoiceState());
        Optional<OptionMapping> channelOptional = Optional.ofNullable(event.getOption("channel"));
        List<Member> memberList = getMemberList(voiceState, channelOptional);
        List<String> nameList = getNameList(namesOptional);
        Map<String, Integer> initiativeMap = new HashMap<>();
        if (validateArguments(deleteDelay, event, memberList, nameList)) return;

        //If we are calling this a second time, it's better to clean slate the DnDHelper for that guild.
        DnDHelper.clearInitiative(requestingUserDto.getGuildId());
        if (!nameList.isEmpty()) {
            rollInitiativeForString(nameList, initiativeMap);
        } else
            rollInitiativeForMembers(memberList, dm, initiativeMap);
        if (checkForNonDmMembersInVoiceChannel(deleteDelay, event)) return;
        displayAllValues(event.getHook());
    }

    private static boolean validateArguments(Integer deleteDelay, SlashCommandInteractionEvent event, List<Member> memberList, List<String> nameList) {
        if (memberList.isEmpty() && nameList.isEmpty()) {
            event
                    .reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it, or give a list of names to roll for.")
                    .setEphemeral(true)
                    .queue(ICommand.invokeDeleteOnHookResponse(deleteDelay));
            return true;
        }
        return false;
    }

    private static boolean checkForNonDmMembersInVoiceChannel(Integer deleteDelay, SlashCommandInteractionEvent event) {
        if (DnDHelper.getSortedEntries().size() == 0) {
            event
                    .reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.")
                    .setEphemeral(true)
                    .queue(ICommand.invokeDeleteOnHookResponse(deleteDelay));
            return true;
        }
        return false;
    }

    @NotNull
    private static List<Member> getMemberList(Optional<GuildVoiceState> voiceState, Optional<OptionMapping> channelOptional) {
        Optional<AudioChannelUnion> audioChannelUnion = voiceState.map(GuildVoiceState::getChannel);
        return channelOptional
                .map(optionMapping -> optionMapping.getAsChannel().asAudioChannel().getMembers())
                .orElseGet(() -> audioChannelUnion.map(IMemberContainer::getMembers).orElse(Collections.emptyList()));
    }

    @NotNull
    private static List<String> getNameList(Optional<String> namesOptional) {
        List<String> namesFromArgs = namesOptional.map(s -> Arrays.stream(s.trim().split(",")).toList()).orElse(Collections.emptyList());
        return namesFromArgs;
    }

    public void displayAllValues(InteractionHook hook) {
        EmbedBuilder embedBuilder = DnDHelper.getInitiativeEmbedBuilder();
        long guildId = hook.getInteraction().getGuild().getIdLong();
        DnDHelper.sendOrEditInitiativeMessage(guildId, hook, embedBuilder);
    }


    @Override
    public String getName() {
        return "initiative";
    }

    @Override
    public String getDescription() {
        return "Roll initiative for members in voice channel. DM is excluded from roll.";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData dm = new OptionData(OptionType.MENTIONABLE, "dm", "Who is the DM? default: caller of command.");
        OptionData voiceChannel = new OptionData(OptionType.CHANNEL, "channel", "which channel is initiative being rolled for? default: voice channel of user calling this.");
        OptionData nameStrings = new OptionData(OptionType.STRING, "names", "to be used as an alternative for the channel option. Comma delimited.");
        return List.of(dm, voiceChannel, nameStrings);
    }
}
