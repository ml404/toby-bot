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
import toby.command.CommandTest;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShhCommandTest implements CommandTest {

    ShhCommand shhCommand;
    
    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        shhCommand = new ShhCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    void test_shhWithValidPermissions_mutesEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        shhSetup(true, true, List.of(targetMember));

        //Act
        shhCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).mute(targetMember, true);

    }

    @Test
    void test_shhWithValidPermissionsAndMultipleMembers_mutesEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        shhSetup(true, true, List.of(member, targetMember));

        //Act
        shhCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).mute(member, true);
        verify(guild, times(1)).mute(targetMember, true);

    }


    @Test
    void test_shhWithInvalidBotPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        shhSetup(false, true, List.of(targetMember));

        //Act
        shhCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("I'm not allowed to mute %s"), eq(targetMember));

    }

    @Test
    void test_shhWithInvalidUserPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        shhSetup(true, false, List.of(targetMember));

        //Act
        shhCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("You aren't allowed to mute %s"), eq(targetMember));

    }

    private static void shhSetup(boolean voiceMuteOtherBot, boolean voiceMuteOtherMember, List<Member> targetMember) {
        AudioChannelUnion audioChannelUnion = mock(AudioChannelUnion.class);
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        AuditableRestAction auditableRestAction = mock(AuditableRestAction.class);
        when(member.canInteract(any(Member.class))).thenReturn(true);
        when(botMember.hasPermission(Permission.VOICE_MUTE_OTHERS)).thenReturn(voiceMuteOtherBot);
        when(member.getVoiceState()).thenReturn(guildVoiceState);
        when(member.hasPermission(Permission.VOICE_MUTE_OTHERS)).thenReturn(voiceMuteOtherMember);
        when(guildVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(audioChannelUnion.getMembers()).thenReturn(targetMember);
        when(guild.mute(any(), eq(true))).thenReturn(auditableRestAction);
        when(auditableRestAction.reason("Muted")).thenReturn(auditableRestAction);
    }
}