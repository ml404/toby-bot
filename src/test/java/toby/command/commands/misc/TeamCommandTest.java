package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.jpa.dto.UserDto;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TeamCommandTest implements CommandTest {

    private TeamCommand teamCommand;

    @BeforeEach
    public void beforeEach() {
        setUpCommonMocks(); // Initialize the mocks
        teamCommand = new TeamCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    public void testHandle_WithNoArgs() {
        // You can set up your test scenario here, including mocking event and UserDto.
        // Example:
        UserDto requestingUserDto = new UserDto(1L, 1L, true, true, true, true, 0L, null); // You can set the user as needed
        Integer deleteDelay = 0;

        // Create a CommandContext
        CommandContext ctx = new CommandContext(event);

        // Test the handle method
        teamCommand.handle(ctx, requestingUserDto, deleteDelay);

        verify(event, times(1)).deferReply();
        verify(event.getHook()).sendMessage("Return X teams from a list of tagged users.");
        verify(interactionHook, times(1)).deleteOriginal();
    }

    @Test
    public void testHandle_WithArgs() {
        // You can set up your test scenario here, including mocking event and UserDto.
        OptionMapping membersOption = Mockito.mock(OptionMapping.class);
        when(event.getOption("members")).thenReturn(membersOption);
        when(membersOption.getAsString()).thenReturn("user1, user2");

        OptionMapping sizeOption = Mockito.mock(OptionMapping.class);
        when(event.getOption("size")).thenReturn(sizeOption);
        when(sizeOption.getAsInt()).thenReturn(2);

        when(event.getOptions()).thenReturn(List.of(membersOption, sizeOption));
        ChannelAction voiceChannel = mock(ChannelAction.class);
        when(guild.createVoiceChannel(anyString())).thenReturn(voiceChannel);
        when(voiceChannel.setBitrate(anyInt())).thenReturn(voiceChannel);
        VoiceChannel createdVoiceChannel = mock(VoiceChannel.class);
        when(voiceChannel.complete()).thenReturn(createdVoiceChannel);
        when(createdVoiceChannel.getName()).thenReturn("channelName");
        Integer deleteDelay = 0;
        // Mock the guild.moveVoiceMember() method

        Mentions mentions = mock(Mentions.class);
        when(event.getOption("members").getMentions()).thenReturn(mentions);
        Member mockMember1 = mock(Member.class);
        Member mockMember2 = mock(Member.class);
        List<Member> memberList = new ArrayList<>(List.of(mockMember1, mockMember2));
        when(mockMember1.getEffectiveName()).thenReturn("Name 1");
        when(mockMember2.getEffectiveName()).thenReturn("Name 2");
        when(mentions.getMembers()).thenReturn(memberList);

        guildMoveVoiceMemberMocking(createdVoiceChannel, mockMember1);
        guildMoveVoiceMemberMocking(createdVoiceChannel, mockMember2);

        UserDto requestingUserDto = new UserDto(1L, 1L, true, true, true, true, 0L, null); // You can set the user as needed

        // Create a CommandContext
        CommandContext ctx = new CommandContext(event);

        // Test the handle method
        teamCommand.handle(ctx, requestingUserDto, deleteDelay);

        verify(event, times(1)).deferReply();
        verify(interactionHook, times(2)).sendMessageFormat(eq("Moved %s to '%s'"), anyString(), anyString());
        verify(interactionHook, times(1)).sendMessage(anyString());
        verify(interactionHook, times(1)).deleteOriginal();
    }

    private static void guildMoveVoiceMemberMocking(VoiceChannel createdVoiceChannel, Member member) {
        when(guild.moveVoiceMember(eq(member), any())).thenAnswer(invocation -> {
            // Simulate the move
            event.getHook().sendMessageFormat("Moved %s to '%s'", member.getEffectiveName(), createdVoiceChannel.getName()).complete();
            return mock(RestAction.class);
        });
    }

}