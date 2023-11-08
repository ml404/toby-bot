package toby.command.commands.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.CommandTest;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KickCommandTest implements CommandTest {

    KickCommand KickCommand;
    
    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        KickCommand = new KickCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    void test_KickWithValidPermissions_kicksEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        kickSetup(true, true, List.of(targetMember));

        //Act
        KickCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).kick(targetMember);

    }

    @Test
    void test_KickWithValidPermissionsAndMultipleMembers_kicksEveryoneInChannel() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        kickSetup(true, true, List.of(member, targetMember));

        //Act
        KickCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(guild, times(1)).kick(member);
        verify(guild, times(1)).kick(targetMember);

    }


    @Test
    void test_KickWithInvalidBotPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        kickSetup(false, true, List.of(targetMember));

        //Act
        KickCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("I'm not allowed to kick %s"), eq(targetMember));

    }

    @Test
    void test_KickWithInvalidUserPermissions_throwsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        kickSetup(true, false, List.of(targetMember));

        //Act
        KickCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(event, times(1)).getGuild();
        verify(interactionHook, times(1)).sendMessageFormat(eq("You can't kick %s"), eq(targetMember));

    }

    private static void kickSetup(boolean botKickOthers, boolean memberKickOthers, List<Member> mentionedMembers) {
        AudioChannelUnion audioChannelUnion = mock(AudioChannelUnion.class);
        GuildVoiceState guildVoiceState = mock(GuildVoiceState.class);
        AuditableRestAction auditableRestAction = mock(AuditableRestAction.class);
        OptionMapping optionMapping = mock(OptionMapping.class);
        Mentions mentions = mock(Mentions.class);
        when(event.getOption("users")).thenReturn(optionMapping);
        when(optionMapping.getMentions()).thenReturn(mentions);
        when(mentions.getMembers()).thenReturn(mentionedMembers);
        when(member.canInteract(any(Member.class))).thenReturn(true);
        when(botMember.hasPermission(Permission.KICK_MEMBERS)).thenReturn(botKickOthers);
        when(botMember.canInteract(any(Member.class))).thenReturn(botKickOthers);
        when(member.getVoiceState()).thenReturn(guildVoiceState);
        when(member.hasPermission(Permission.KICK_MEMBERS)).thenReturn(memberKickOthers);
        when(guildVoiceState.getChannel()).thenReturn(audioChannelUnion);
        when(guild.kick(any())).thenReturn(auditableRestAction);
        when(auditableRestAction.reason(eq("because you told me to."))).thenReturn(auditableRestAction);
    }
}