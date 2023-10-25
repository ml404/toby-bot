package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TalkCommandTest implements CommandTest {

    TalkCommand talkCommand;
    
    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        talkCommand = new TalkCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    void test_talkWithValidPermissions_unmutesEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        talkSetup(true, true, List.of(targetMember));

        //Act
        talkCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).mute(targetMember, false);

    }

    @Test
    void test_talkWithValidPermissionsAndMultipleMembers_unmutesEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        talkSetup(true, true, List.of(member, targetMember));

        //Act
        talkCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).mute(member, false);
        verify(guild, times(1)).mute(targetMember, false);

    }


    @Test
    void test_talkWithInvalidBotPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        talkSetup(false, true, List.of(targetMember));

        //Act
        talkCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("I'm not allowed to unmute %s"), eq(targetMember));

    }

    @Test
    void test_talkWithInvalidUserPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        talkSetup(true, false, List.of(targetMember));

        //Act
        talkCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("You aren't allowed to unmute %s"), eq(targetMember));

    }

    private static void talkSetup(boolean voiceMuteOtherBot, boolean voiceMuteOtherMember, List<Member> targetMember) {
        AudioChannelUnion audioChannelUnion = mock(AudioChannelUnion.class);
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        AuditableRestAction auditableRestAction = mock(AuditableRestAction.class);
        when(member.canInteract(any(Member.class))).thenReturn(true);
        when(botMember.hasPermission(Permission.VOICE_MUTE_OTHERS)).thenReturn(voiceMuteOtherBot);
        when(member.getVoiceState()).thenReturn(guildVoiceState);
        when(member.hasPermission(Permission.VOICE_MUTE_OTHERS)).thenReturn(voiceMuteOtherMember);
        when(guildVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(audioChannelUnion.getMembers()).thenReturn(targetMember);
        when(guild.mute(any(), eq(false))).thenReturn(auditableRestAction);
        when(auditableRestAction.reason("Unmuted")).thenReturn(auditableRestAction);
    }
}