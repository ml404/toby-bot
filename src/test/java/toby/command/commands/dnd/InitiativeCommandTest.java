package toby.command.commands.dnd;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.command.CommandTest;
import toby.jpa.service.IUserService;
import toby.jpa.service.impl.UserServiceImpl;

import java.util.List;

import static org.mockito.Mockito.*;

class InitiativeCommandTest implements CommandTest {

    InitiativeCommand initiativeCommand;

    @Mock
    IUserService userService;

    @BeforeEach
    public void setup() {
        setUpCommonMocks();
        userService = mock(UserServiceImpl.class);
        doReturn(webhookMessageCreateAction)
                .when(interactionHook)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));

        initiativeCommand = new InitiativeCommand(userService);
    }

    @AfterEach
    public void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    void test_initiativeCommandWithCorrectSetup_WithMembers() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping channelOptionMapping = mock(OptionMapping.class);
        OptionMapping dmOptionMapping = mock(OptionMapping.class);
        GuildChannelUnion guildChannelUnion = mock(GuildChannelUnion.class);
        AudioChannel audioChannel = mock(AudioChannel.class);
        Member dmMember = mock(Member.class);
        Interaction interaction = mock(Interaction.class);
        when(channelOptionMapping.getAsChannel()).thenReturn(guildChannelUnion);
        when(dmOptionMapping.getAsMember()).thenReturn(dmMember);
        when(guildChannelUnion.asAudioChannel()).thenReturn(audioChannel);
        when(event.getOption("channel")).thenReturn(channelOptionMapping);
        when(event.getOption("dm")).thenReturn(dmOptionMapping);
        when(audioChannel.getMembers()).thenReturn(List.of(member));
        when(interactionHook.getInteraction()).thenReturn(interaction);
        when(interaction.getGuild()).thenReturn(guild);
        when(webhookMessageCreateAction.setActionRow(any(Button.class), any(Button.class), any(Button.class))).thenReturn(webhookMessageCreateAction);
        when(member.getUser()).thenReturn(user);


        //Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(webhookMessageCreateAction, times(1)).setActionRow(any(), any(), any());

    }

    @Test
    void test_initiativeCommandWithCorrectSetup_WithNames() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping namesMapping = mock(OptionMapping.class);
        OptionMapping dmOptionMapping = mock(OptionMapping.class);
        Member dmMember = mock(Member.class);
        Interaction interaction = mock(Interaction.class);
        when(namesMapping.getAsString()).thenReturn("name1, name2, name3, name4");
        when(dmOptionMapping.getAsMember()).thenReturn(dmMember);
        when(event.getOption("names")).thenReturn(namesMapping);
        when(event.getOption("dm")).thenReturn(dmOptionMapping);
        when(interactionHook.getInteraction()).thenReturn(interaction);
        when(interaction.getGuild()).thenReturn(guild);
        when(webhookMessageCreateAction.setActionRow(any(Button.class), any(Button.class), any(Button.class))).thenReturn(webhookMessageCreateAction);
        when(member.getUser()).thenReturn(user);


        //Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(webhookMessageCreateAction, times(1)).setActionRow(any(), any(), any());

    }

    @Test
    void test_initiativeCommandWithCorrectSetup_UsingMemberVoiceState() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping dmOptionMapping = mock(OptionMapping.class);
        GuildChannelUnion guildChannelUnion = mock(GuildChannelUnion.class);
        AudioChannel audioChannel = mock(AudioChannel.class);
        Member dmMember = mock(Member.class);
        Interaction interaction = mock(Interaction.class);
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        AudioChannelUnion audioChannelUnion = mock(AudioChannelUnion.class);
        when(dmOptionMapping.getAsMember()).thenReturn(dmMember);
        when(guildChannelUnion.asAudioChannel()).thenReturn(audioChannel);
        when(event.getOption("dm")).thenReturn(dmOptionMapping);
        when(member.getVoiceState()).thenReturn(guildVoiceState);
        when(guildVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(audioChannelUnion.getMembers()).thenReturn(List.of(member));
        when(interactionHook.getInteraction()).thenReturn(interaction);
        when(interaction.getGuild()).thenReturn(guild);
        when(webhookMessageCreateAction.setActionRow(any(Button.class), any(Button.class), any(Button.class))).thenReturn(webhookMessageCreateAction);
        when(member.getUser()).thenReturn(user);

        //Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(webhookMessageCreateAction, times(1)).setActionRow(any(), any(), any());

    }

    @Test
    void test_initiativeCommandWithNoValidChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping dmOptionMapping = mock(OptionMapping.class);
        AudioChannel audioChannel = mock(AudioChannel.class);
        Member dmMember = mock(Member.class);
        Interaction interaction = mock(Interaction.class);
        when(dmOptionMapping.getAsMember()).thenReturn(dmMember);
        when(event.getOption("dm")).thenReturn(dmOptionMapping);
        when(audioChannel.getMembers()).thenReturn(List.of(member));
        when(interactionHook.getInteraction()).thenReturn(interaction);
        when(interaction.getGuild()).thenReturn(guild);
        when(event.reply(anyString())).thenReturn(replyCallbackAction);
        when(replyCallbackAction.setEphemeral(true)).thenReturn(replyCallbackAction);

        //Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(0)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(webhookMessageCreateAction, times(0)).setActionRow(any(), any(), any());
        verify(event, times(1)).reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it, or give a list of names to roll for.");
    }

    @Test
    void test_initiativeCommandWithNoNonDMMembersAndAValidChannelOption() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping channelOptionMapping = mock(OptionMapping.class);
        OptionMapping dmOptionMapping = mock(OptionMapping.class);
        GuildChannelUnion guildChannelUnion = mock(GuildChannelUnion.class);
        AudioChannel audioChannel = mock(AudioChannel.class);
        Member dmMember = mock(Member.class);
        Interaction interaction = mock(Interaction.class);
        when(channelOptionMapping.getAsChannel()).thenReturn(guildChannelUnion);
        when(dmOptionMapping.getAsMember()).thenReturn(dmMember);
        when(guildChannelUnion.asAudioChannel()).thenReturn(audioChannel);
        when(event.getOption("channel")).thenReturn(channelOptionMapping);
        when(event.getOption("dm")).thenReturn(dmOptionMapping);
        when(audioChannel.getMembers()).thenReturn(List.of(dmMember));
        when(interactionHook.getInteraction()).thenReturn(interaction);
        when(interaction.getGuild()).thenReturn(guild);
        when(event.reply(anyString())).thenReturn(replyCallbackAction);
        when(replyCallbackAction.setEphemeral(true)).thenReturn(replyCallbackAction);

        //Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(0)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(webhookMessageCreateAction, times(0)).setActionRow(any(), any(), any());
        verify(event, times(1)).reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.");
    }
}