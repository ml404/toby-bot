package toby.command.commands.moderation;

import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.command.CommandTest;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.List;

import static org.mockito.Mockito.*;

class SocialCreditCommandTest implements CommandTest {

    SocialCreditCommand socialCreditCommand;

    @Mock
    IUserService userService = mock(IUserService.class);

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        socialCreditCommand = new SocialCreditCommand(userService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
        reset(userService);
    }


    @Test
    void test_socialCreditCommandWithNoArgs_printsRequestingUserDtoScore() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        when(guild.isLoaded()).thenReturn(false);


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("%s's social credit is: %d"), eq("Effective Name"), eq(0L));
    }

    @Test
    void test_socialCreditCommandWithUserMentionedAndCorrectPermissions_printsRequestingUserDtoScore() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        UserDto targetUserDto = mock(UserDto.class);
        when(guild.isLoaded()).thenReturn(false);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(userOptionMapping.getAsUser()).thenReturn(user);
        when(userService.getUserById(anyLong(), anyLong())).thenReturn(targetUserDto);
        when(targetUserDto.getGuildId()).thenReturn(1L);
        when(member.isOwner()).thenReturn(true);


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("%s's social credit is: %d"), eq("UserName"), eq(0L));
    }

    @Test
    void test_socialCreditCommandWithUserMentionedAndCorrectPermissionsAndValueToAdjust_printsAdjustingUserDtoScore() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        OptionMapping scOptionMapping = mock(OptionMapping.class);
        UserDto targetUserDto = mock(UserDto.class);
        when(guild.isLoaded()).thenReturn(false);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(event.getOption("credit")).thenReturn(scOptionMapping);
        when(userOptionMapping.getAsUser()).thenReturn(user);
        when(scOptionMapping.getAsLong()).thenReturn(5L);
        when(userService.getUserById(anyLong(), anyLong())).thenReturn(targetUserDto);
        when(targetUserDto.getGuildId()).thenReturn(1L);
        when(member.isOwner()).thenReturn(true);
        when(userService.updateUser(targetUserDto)).thenReturn(targetUserDto);
        when(targetUserDto.getSocialCredit()).thenReturn(5L);


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("Updated user %s's social credit by %d. New score is: %d"), eq("UserName"), eq(5L), eq(5L));
    }

    @Test
    void test_socialCreditCommandWithUserMentionedAndIncorrectPermissions_printsRequestingUserDtoScore() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        UserDto targetUserDto = mock(UserDto.class);
        when(guild.isLoaded()).thenReturn(false);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(userOptionMapping.getAsUser()).thenReturn(user);
        when(userService.getUserById(anyLong(), anyLong())).thenReturn(targetUserDto);
        when(member.isOwner()).thenReturn(false);
        when(targetUserDto.getGuildId()).thenReturn(1L);


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("User '%s' is not allowed to adjust the social credit of user '%s'."), eq("Effective Name"), eq("UserName"));
    }

    @Test
    void test_leaderboard_printsLeaderboard(){
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        UserDto targetUserDto = mock(UserDto.class);
        when(guild.isLoaded()).thenReturn(false);
        OptionMapping leaderOptionMapping = mock(OptionMapping.class);
        when(event.getOption("leaderboard")).thenReturn(leaderOptionMapping);
        when(leaderOptionMapping.getAsBoolean()).thenReturn(true);
        when(userService.getUserById(anyLong(), anyLong())).thenReturn(targetUserDto);
        when(member.isOwner()).thenReturn(false);
        when(targetUserDto.getGuildId()).thenReturn(1L);
        when(userService.listGuildUsers(1L)).thenReturn(List.of(requestingUserDto, targetUserDto));
        when(requestingUserDto.getSocialCredit()).thenReturn(100L);
        when(targetUserDto.getSocialCredit()).thenReturn(50L);
        when(targetUserDto.getDiscordId()).thenReturn(2L);
        when(guild.getMembers()).thenReturn(List.of(member, targetMember));
        when(member.getIdLong()).thenReturn(1L);
        when(targetMember.getIdLong()).thenReturn(2L);



        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("**Social Credit Leaderboard**\n**-----------------------------**\n#1: Effective Name - score: 100\n#2: Target Effective Name - score: 50\n"));
    }
}