package toby.command.commands.dnd;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
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

public class InitiativeCommand implements IDnDCommand {

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        Member dm = Optional.ofNullable(event.getOption("dm").getAsMember()).orElse(ctx.getMember());
        GuildVoiceState voiceState = member.getVoiceState();
        Optional<OptionMapping> channelOptional = Optional.ofNullable(event.getOption("channel"));
        List<Member> memberList = getMemberList(voiceState, channelOptional);
        Map<Member, Integer> initiativeMap = new HashMap<>();
        if (memberList.isEmpty()) {
            event
                    .reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it.")
                    .setEphemeral(true)
                    .queue(ICommand.invokeDeleteOnHookResponse(deleteDelay));
            return;
        }
        //If we are calling this a second time, it's better to clean slate the DnDHelper for that guild.
        DnDHelper.clearInitiative(requestingUserDto.getGuildId());
        rollInitiativeForMembers(memberList, dm, initiativeMap);
        if(DnDHelper.getSortedEntries().size() == 0){
            event
                    .reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.")
                    .setEphemeral(true)
                    .queue(ICommand.invokeDeleteOnHookResponse(deleteDelay));
            return;
        }
        displayAllValues(event.getHook());
    }

    @NotNull
    private static List<Member> getMemberList(GuildVoiceState voiceState, Optional<OptionMapping> channelOptional) {
        return channelOptional
                .map(optionMapping -> optionMapping.getAsChannel().asAudioChannel().getMembers())
                .orElseGet(() -> voiceState != null ? voiceState.getChannel().getMembers() : Collections.emptyList());
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
        return List.of(dm, voiceChannel);
    }
}
