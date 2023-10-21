package toby.command.commands.misc;

import commands.CommandTest;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

public class UserInfoCommandTest implements CommandTest {

    @Mock
    private IUserService userService;

    private UserInfoCommand userInfoCommand;

    @BeforeEach
    public void setUp() {
        setUpCommonMocks(); // Call the common setup defined in the interface

        // Additional setup specific to UserInfoCommandTest
        userService = mock(IUserService.class);
        userInfoCommand = new UserInfoCommand(userService);
        when(event.getGuild()).thenReturn(mock(Guild.class));
    }

    @Test
    public void testHandleCommandWithOwnUser() {

        // Mock the event's options to be empty
        when(event.getOptions()).thenReturn(List.of());

        // Mock the requesting user's DTO
        UserDto requestingUserDto = new UserDto();
        when(userService.getUserById(anyLong(), anyLong())).thenReturn(requestingUserDto);
        requestingUserDto.setMusicDto(new MusicDto());
        when(event.getGuild().getIdLong()).thenReturn(123L);

        // Test handle method
        userInfoCommand.handle(new CommandContext(event), requestingUserDto, 0);

        // Verify interactions
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq(requestingUserDto));
        verify(interactionHook, times(1)).sendMessage(anyString());
        verify(userService, times(0)).getUserById(anyLong(), anyLong());
    }

    @Test
    public void testHandleCommandWithMentionedUserAndValidRequestingPermissions() {
        // Mock user interaction with mentioned user

        // Mock the event's options to include mentions
        when(event.getOptions()).thenReturn(List.of(mock(OptionMapping.class))); // Add an option to simulate mentions

        // Mock a mentioned user's DTO
        UserDto mentionedUserDto = new UserDto();
        when(userService.getUserById(anyLong(), anyLong())).thenReturn(mentionedUserDto);
        mentionedUserDto.setMusicDto(new MusicDto());

        // Mock mentions
        when(event.getOption(anyString())).thenReturn(mock(OptionMapping.class));
        Mentions mentions = mock(Mentions.class);
        when(event.getOption("users").getMentions()).thenReturn(mentions);
        Member mockMember = mock(Member.class);
        List<Member> memberList = List.of(mockMember);
        when(mentions.getMembers()).thenReturn(memberList);
        Guild mockGuild = mock(Guild.class);
        when(mockMember.getGuild()).thenReturn(mockGuild);
        when(mockGuild.getIdLong()).thenReturn(123L);
        UserDto mentionedUserMock = mock(UserDto.class);
        when(userService.getUserById(anyLong(), anyLong())).thenReturn(mentionedUserMock);
        when(mentionedUserMock.getMusicDto()).thenReturn(new MusicDto(1L, 1L, "filename", 10, null));
        when(mockMember.getEffectiveName()).thenReturn("Toby");


        // Test handle method
        UserDto userDto = mock(UserDto.class);
        when(userDto.isSuperUser()).thenReturn(true);
        userInfoCommand.handle(new CommandContext(event), userDto, 0);

        // Verify interactions

        //lookup on mentionedMember
        verify(userService, times(1)).getUserById(anyLong(), anyLong());
        //music file message
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), anyString());
        //mentioned user message
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq("Toby"), eq(mentionedUserMock));
    }

    @Test
    public void testHandleCommandNoPermission() {
        // Mock user interaction without permission

        // Mock the event's options to include mentions
        when(event.getOptions()).thenReturn(List.of(mock(OptionMapping.class)));

        // Mock the requesting user without permission
        UserDto requestingUserDto = mock(UserDto.class);
        when(requestingUserDto.isSuperUser()).thenReturn(false);

        // Test handle method
        userInfoCommand.handle(new CommandContext(event), requestingUserDto, 0);

        // Verify interactions
        verify(interactionHook, times(1)).sendMessage(anyString());
    }
}