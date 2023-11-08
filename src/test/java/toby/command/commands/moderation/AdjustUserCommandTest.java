package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.Mentions;
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

class AdjustUserCommandTest implements CommandTest {

    AdjustUserCommand adjustUserCommand;

    @Mock
    IUserService userService = mock(IUserService.class);

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        adjustUserCommand = new AdjustUserCommand(userService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
        reset(userService);
    }

    @Test
    void testAdjustUser_withCorrectPermissions_updatesTargetUser() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        UserDto targetUserDto = mock(UserDto.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        OptionMapping permissionOptionMapping = mock(OptionMapping.class);
        Mentions mentions = mock(Mentions.class);
        when(userService.getUserById(any(),any())).thenReturn(targetUserDto);
        when(targetUserDto.getGuildId()).thenReturn(1L);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(event.getOption("name")).thenReturn(permissionOptionMapping);
        when(userOptionMapping.getMentions()).thenReturn(mentions);
        when(permissionOptionMapping.getAsString()).thenReturn(UserDto.Permissions.MUSIC.name());
        when(mentions.getMembers()).thenReturn(List.of(targetMember));

        //Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(userService, times(1)).getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
        verify(userService, times(1)).updateUser(targetUserDto);
        verify(interactionHook, times(1)).sendMessageFormat(eq("Updated user %s's permissions"), eq("Target Effective Name"));

    }

    @Test
    void testAdjustUser_withCorrectPermissions_createsTargetUser() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        UserDto targetUserDto = mock(UserDto.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        OptionMapping permissionOptionMapping = mock(OptionMapping.class);
        Mentions mentions = mock(Mentions.class);
        when(targetUserDto.getGuildId()).thenReturn(1L);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(event.getOption("name")).thenReturn(permissionOptionMapping);
        when(userOptionMapping.getMentions()).thenReturn(mentions);
        when(permissionOptionMapping.getAsString()).thenReturn(UserDto.Permissions.MUSIC.name());
        when(mentions.getMembers()).thenReturn(List.of(targetMember));

        //Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(userService, times(1)).getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
        verify(userService, times(1)).createNewUser(any(UserDto.class));
        verify(interactionHook, times(1)).sendMessageFormat(eq("User %s's permissions did not exist in this server's database, they have now been created"), eq("Target Effective Name"));

    }

    @Test
    void testAdjustUser_withNoMentionedPermissions_Errors() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        UserDto targetUserDto = mock(UserDto.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        Mentions mentions = mock(Mentions.class);
        when(userService.getUserById(any(),any())).thenReturn(targetUserDto);
        when(targetUserDto.getGuildId()).thenReturn(1L);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(userOptionMapping.getMentions()).thenReturn(mentions);
        when(mentions.getMembers()).thenReturn(List.of(targetMember));

        //Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(userService, times(0)).getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
        verify(interactionHook, times(1)).sendMessage(eq("You must mention a permission to adjust of the user you've mentioned."));

    }

    @Test
    void testAdjustUser_withNoMentionedUser_Errors() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        UserDto targetUserDto = mock(UserDto.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        when(userService.getUserById(any(),any())).thenReturn(targetUserDto);
        when(event.getOption("users")).thenReturn(userOptionMapping);

        //Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(userService, times(0)).getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
        verify(interactionHook, times(1)).sendMessage(eq("You must mention 1 or more Users to adjust permissions of"));

    }

    @Test
    void testAdjustUser_whenUserIsntOwner_Errors() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        UserDto targetUserDto = mock(UserDto.class);
        OptionMapping userOptionMapping = mock(OptionMapping.class);
        when(userService.getUserById(any(),any())).thenReturn(targetUserDto);
        when(event.getOption("users")).thenReturn(userOptionMapping);
        when(member.isOwner()).thenReturn(false);
        when(requestingUserDto.isSuperUser()).thenReturn(false);

        //Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(userService, times(0)).getUserById(targetMember.getIdLong(), targetMember.getGuild().getIdLong());
        verify(interactionHook, times(1)).sendMessage(eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"));

    }
}