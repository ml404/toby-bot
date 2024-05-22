package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.command.CommandTest;
import toby.jpa.dto.ConfigDto;
import toby.jpa.service.IConfigService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoveCommandTest implements CommandTest {

    MoveCommand moveCommand;
    
    @Mock
    IConfigService configService;
    
    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        configService = mock(IConfigService.class);
        when(configService.getConfigByName(ConfigDto.Configurations.MOVE.getConfigValue(),"1")).thenReturn(new ConfigDto(ConfigDto.Configurations.MOVE.getConfigValue(), "CHANNEL","1"));
        moveCommand = new MoveCommand(configService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
        reset(configService);
    }

    @Test
    void test_moveWithValidPermissions_movesEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        moveSetup(true, true, List.of(targetMember));

        //Act
        moveCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).moveVoiceMember(eq(targetMember),any());

    }

    @Test
    void test_moveWithValidPermissionsAndMultipleMembers_movesEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        moveSetup(true, true, List.of(member, targetMember));

        //Act
        moveCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).moveVoiceMember(eq(member), any());
        verify(guild, times(1)).moveVoiceMember(eq(targetMember),any());

    }


    @Test
    void test_moveWithInvalidBotPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        moveSetup(false, true, List.of(targetMember));
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        when(targetMember.getVoiceState()).thenReturn(guildVoiceState);
        when(guildVoiceState.inAudioChannel()).thenReturn(true);

        //Act
        moveCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("I'm not allowed to move %s"), eq("Target Effective Name"));

    }

    @Test
    void test_moveWithInvalidUserPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        moveSetup(true, false, List.of(targetMember));

        //Act
        moveCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("You can't move '%s'"), eq("Target Effective Name"));

    }

    @Test
    void test_moveWithUserNotInChannel_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        moveSetup(true, true, List.of(targetMember));
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        when(targetMember.getVoiceState()).thenReturn(guildVoiceState);
        when(guildVoiceState.inAudioChannel()).thenReturn(false);

        //Act
        moveCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("Mentioned user '%s' is not connected to a voice channel currently, so cannot be moved."), eq("Target Effective Name"));

    }

    private static void moveSetup(boolean botMoveOthers, boolean memberMoveOthers, List<Member> mentionedMembers) {
        AudioChannelUnion audioChannelUnion = mock(AudioChannelUnion.class);
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        AuditableRestAction auditableRestAction = mock(AuditableRestAction.class);
        OptionMapping channelOptionMapping = mock(OptionMapping.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        Mentions mentions = mock(Mentions.class);

        when(targetMember.getVoiceState()).thenReturn(guildVoiceState);
        when(guildVoiceState.inAudioChannel()).thenReturn(true);
        when(event.getOption("channel")).thenReturn(channelOptionMapping);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(userOptionMapping.getMentions()).thenReturn(mentions);
        GuildChannelUnion guildChannelUnion = mock(GuildChannelUnion.class);
        when(channelOptionMapping.getAsChannel()).thenReturn(guildChannelUnion);
        when(guildChannelUnion.getName()).thenReturn("Channel");
        when(guildChannelUnion.asVoiceChannel()).thenReturn(mock(VoiceChannel.class));
        when(mentions.getMembers()).thenReturn(mentionedMembers);
        when(member.canInteract(any(Member.class))).thenReturn(true);
        when(botMember.hasPermission(Permission.VOICE_MOVE_OTHERS)).thenReturn(botMoveOthers);
        when(botMember.canInteract(any(Member.class))).thenReturn(botMoveOthers);
        when(member.getVoiceState()).thenReturn(guildVoiceState);
        when(member.hasPermission(Permission.VOICE_MOVE_OTHERS)).thenReturn(memberMoveOthers);
        when(guildVoiceState.getChannel()).thenReturn(audioChannelUnion);
        VoiceChannel voiceChannel = mock(VoiceChannel.class);
        when(guild.getVoiceChannelsByName("CHANNEL", true)).thenReturn(List.of(voiceChannel));
        when(guild.moveVoiceMember(any(), any())).thenReturn(auditableRestAction);
        when(auditableRestAction.reason(any())).thenReturn(auditableRestAction);
    }
}