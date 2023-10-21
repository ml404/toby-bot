package commands.misc;

import commands.CommandTest;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import toby.command.CommandContext;
import toby.command.commands.misc.BrotherCommand;
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
    private Guild guild;

    @Mock
    private Emoji tobyEmote;

    @Mock
    private JDA jda;

    private BrotherCommand brotherCommand;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        brotherService = mock(IBrotherService.class);
        guild = mock(Guild.class);
        tobyEmote = mock(RichCustomEmoji.class);
        jda = mock(JDA.class);

        // Initialize the BrotherCommand with the mocked dependencies
        brotherCommand = new BrotherCommand(brotherService);
    }

    @AfterEach
    void tearDown(){
        tearDownCommonMocks();
        reset(guild);
        reset(jda);
    }

    @Test
    void testDetermineBrother_BrotherExistsWithNoMention() {
        // Arrange
        Optional<Mentions> mentions = Optional.empty();
        UserDto user = mock(UserDto.class);
        BrotherDto brotherDto = new BrotherDto();
        brotherDto.setBrotherName("TestBrother");

        OptionMapping optionMapping = mock(OptionMapping.class);
        Mockito.when(event.getOption("brother")).thenReturn(optionMapping);
        Mockito.when(event.getUser()).thenReturn(mock(User.class));
        Mockito.when(brotherService.getBrotherById(user.getDiscordId())).thenReturn(Optional.of(brotherDto));
        Mockito.when(event.getGuild()).thenReturn(guild);
        Mockito.when(guild.getJDA()).thenReturn(jda);
        Mockito.when(jda.getEmojiById(Emotes.TOBY)).thenReturn((RichCustomEmoji) tobyEmote);

        // Act
        brotherCommand.handle(new CommandContext(event), null, 0);

        // Assert
        // Verify that the expected response is sent
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq(brotherDto.getBrotherName()));
        // You can use Mockito.verify and ArgumentMatchers to assert the behavior
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
        Mockito.when(event.getOption("brother")).thenReturn(optionMapping);
        Mockito.when(event.getUser()).thenReturn(user);
        Mockito.when(user.getName()).thenReturn("userName");
        Mockito.when(brotherService.getBrotherById(userDto.getDiscordId())).thenReturn(Optional.empty());
        Mockito.when(event.getGuild()).thenReturn(guild);
        Mockito.when(guild.getJDA()).thenReturn(jda);
        Mockito.when(jda.getEmojiById(Emotes.TOBY)).thenReturn((RichCustomEmoji) tobyEmote);

        // Act
        brotherCommand.handle(new CommandContext(event), null, 0);

        // Assert
        // Sends an angry message saying you're not my fucking brother with the toby emoji
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), anyString(), eq(tobyEmote));
        // You can use Mockito.verify and ArgumentMatchers to assert the behavior
    }

    @Test
    void testDetermineBrother_NoBrother() {
        // Similar to the previous test but for the case where no brother is found
    }
}
