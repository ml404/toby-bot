package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.emote.Emotes;
import toby.jpa.dto.BrotherDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IBrotherService;

import java.util.Optional;

import static org.mockito.Mockito.*;

class BrotherCommandTest implements CommandTest {

    @Mock
    private IBrotherService brotherService;

    @Mock
    private Emoji tobyEmote;

    private BrotherCommand brotherCommand;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        brotherService = mock(IBrotherService.class);
        tobyEmote = mock(RichCustomEmoji.class);
        brotherCommand = new BrotherCommand(brotherService);
    }

    @AfterEach
    void tearDown(){
        tearDownCommonMocks();
        reset(brotherService);
        reset(tobyEmote);
    }

    @Test
    void testDetermineBrother_BrotherExistsWithNoMention() {
        Optional<Mentions> mentions = Optional.empty();
        UserDto user = mock(UserDto.class);
        BrotherDto brotherDto = new BrotherDto();
        brotherDto.setBrotherName("TestBrother");

        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOption("brother")).thenReturn(optionMapping);
        when(event.getUser()).thenReturn(mock(User.class));
        when(brotherService.getBrotherById(user.getDiscordId())).thenReturn(Optional.of(brotherDto));
        when(event.getGuild()).thenReturn(guild);
        when(jda.getEmojiById(Emotes.TOBY)).thenReturn((RichCustomEmoji) tobyEmote);

        // Act
        brotherCommand.handle(new CommandContext(event), null, 0);

        // Assert
        // Verify that the expected response is sent
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq(brotherDto.getBrotherName()));
    }

    @Test
    void testDetermineBrother_BrotherDoesntExistWithNoMention() {
        // Arrange
        Optional<Mentions> mentions = Optional.empty();
        UserDto userDto = mock(UserDto.class);
        User user = mock(User.class);
        BrotherDto brotherDto = new BrotherDto();
        brotherDto.setBrotherName("TestBrother");

        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOption("brother")).thenReturn(optionMapping);
        when(event.getUser()).thenReturn(user);
        when(user.getName()).thenReturn("userName");
        when(brotherService.getBrotherById(userDto.getDiscordId())).thenReturn(Optional.empty());
        when(event.getGuild()).thenReturn(guild);
        when(guild.getJDA()).thenReturn(jda);
        when(jda.getEmojiById(Emotes.TOBY)).thenReturn((RichCustomEmoji) tobyEmote);

        // Act
        brotherCommand.handle(new CommandContext(event), null, 0);

        // Assert
        // Sends an angry message saying you're not my fucking brother with the toby emoji
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), anyString(), eq(tobyEmote));

    }

    @Test
    void testDetermineBrother_CalledByToby() {
        //Arrange
        UserDto userDto = mock(UserDto.class);
        User user = mock(User.class);
        BrotherDto brotherDto = new BrotherDto();
        brotherDto.setBrotherName("TestBrother");

        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getUser()).thenReturn(user);
        when(event.getOption("brother")).thenReturn(optionMapping);
        when(event.getUser()).thenReturn(user);
        when(user.getIdLong()).thenReturn(BrotherCommand.tobyId);
        when(brotherService.getBrotherById(userDto.getDiscordId())).thenReturn(Optional.empty());
        when(event.getGuild()).thenReturn(guild);
        when(guild.getJDA()).thenReturn(jda);
        when(jda.getEmojiById(Emotes.TOBY)).thenReturn((RichCustomEmoji) tobyEmote);


        // Act
        brotherCommand.handle(new CommandContext(event), null, 0);

        // Assert
        // Sends 'You're not my fucking brother Toby, you're me'
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq(tobyEmote));
    }
}
