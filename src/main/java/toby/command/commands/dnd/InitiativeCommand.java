package toby.command.commands.dnd;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.helpers.DnDHelper;
import toby.jpa.dto.UserDto;

import java.awt.*;
import java.util.List;
import java.util.*;

import static toby.helpers.DnDHelper.createTurnOrderString;
import static toby.helpers.DnDHelper.rollInitiativeForMembers;

public class InitiativeCommand implements IDnDCommand {

    @Override
    public void handle(CommandContext ctx, UserDto requestingUserDto, Integer deleteDelay) {
        final SlashCommandInteractionEvent event = ctx.getEvent();
        event.deferReply().queue();
        final Member member = ctx.getMember();
        Member dm = Optional.ofNullable(event.getOption("dm").getAsMember()).orElse(ctx.getMember());
        GuildVoiceState voiceState = member.getVoiceState();
        List<Member> memberList = Optional
                .ofNullable(event.getOption("channel").getAsChannel().asAudioChannel().getMembers())
                .orElse(voiceState != null ? voiceState.getChannel().getMembers() : Collections.emptyList());
        Map<Member, Integer> initiativeMap = new HashMap<>();
        if (memberList.isEmpty()) {
            event
                    .reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it.")
                    .setEphemeral(true)
                    .queue(ICommand.invokeDeleteOnHookResponse(deleteDelay));
            return;
        }
        rollInitiativeForMembers(memberList, dm, initiativeMap);
        displayAllValues(event.getHook());
    }

    public void displayAllValues(InteractionHook hook) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setColor(Color.GREEN);
        embedBuilder.setTitle("Initiative Order");
        StringBuilder description = createTurnOrderString();
        embedBuilder.setDescription(description.toString());
        long guildId = hook.getInteraction().getGuild().getIdLong();
        Message currentMessage = DnDHelper.getCurrentMessage(guildId);
        if (currentMessage == null) {
            hook.sendMessageEmbeds(embedBuilder.build())
                    .setActionRow(Button.primary("init:iterate", "Next")).queue(message -> DnDHelper.setCurrentMessage(guildId, message));
        } else {
            currentMessage.editMessageEmbeds(embedBuilder.build()).queue();
        }
    }



    @Override
    public String getName() {
        return "initiative";
    }

    @Override
    public String getDescription() {
        return "Roll initiative for the mentioned members. Defaults to the voice channel connected members of the person who calls this command. \n" +
                "Will exclude who called this method as the DM, unless another member is tagged as DM.";
    }

    @Override
    public List<OptionData> getOptionData() {
        OptionData dm = new OptionData(OptionType.MENTIONABLE, "dm", "Who is the DM? (to be excluded from initiative roll).");
        OptionData voiceChannel = new OptionData(OptionType.CHANNEL, "channel", "which channel is initiative being rolled for? defaults to the voice channel of the person calling this.");
        return List.of(dm, voiceChannel);
    }
}
