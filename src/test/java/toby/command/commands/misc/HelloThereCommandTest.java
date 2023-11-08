package toby.command.commands.misc;

import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import toby.command.CommandContext;
import toby.command.CommandTest;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IConfigService;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HelloThereCommandTest implements CommandTest {

    HelloThereCommand command;
    @Mock
    IConfigService configService;
    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        configService = mock(IConfigService.class);
        command = new HelloThereCommand(configService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    public void testWithOptionAfterEp3(){
        OptionMapping messageOption = Mockito.mock(OptionMapping.class);
        when(messageOption.getAsString()).thenReturn("2005/05/20");
        when(event.getOption("date")).thenReturn(messageOption);

        UserDto requestingUserDto = new UserDto(1L, 1L, true, true, true, true, 0L, null); // You can set the user as needed

        // Mock the event to return the MESSAGE option
        when(event.getOptions()).thenReturn(List.of(messageOption));
        when(event.getOption(anyString())).thenReturn(messageOption);
        when(guild.getId()).thenReturn("1");
        when(configService.getConfigByName("DATEFORMAT", "1")).thenReturn(new ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"));

        command.handle(new CommandContext(event), requestingUserDto, 0);

        verify(interactionHook, times(1)).sendMessage("General Kenobi.");
    }

    @Test
    public void testWithOptionBeforeEp3(){
        OptionMapping messageOption = Mockito.mock(OptionMapping.class);
        when(messageOption.getAsString()).thenReturn("2005/05/18");
        when(event.getOption("date")).thenReturn(messageOption);

        UserDto requestingUserDto = new UserDto(1L, 1L, true, true, true, true, 0L, null); // You can set the user as needed

        // Mock the event to return the MESSAGE option

        when(event.getOptions()).thenReturn(List.of(messageOption));
        when(event.getOption(anyString())).thenReturn(messageOption);
        when(guild.getId()).thenReturn("1");
        when(configService.getConfigByName("DATEFORMAT", "1")).thenReturn(new ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"));

        command.handle(new CommandContext(event), requestingUserDto, 0);

        verify(interactionHook, times(1)).sendMessage("Hello.");
    }

    @Test
    public void testWithNoOption(){
        OptionMapping messageOption = Mockito.mock(OptionMapping.class);

        UserDto requestingUserDto = new UserDto(1L, 1L, true, true, true, true, 0L, null); // You can set the user as needed

        // Mock the event to return the MESSAGE option
        when(event.getOption(anyString())).thenReturn(messageOption);
        when(guild.getId()).thenReturn("1");
        when(configService.getConfigByName("DATEFORMAT", "1")).thenReturn(new ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"));

        command.handle(new CommandContext(event), requestingUserDto, 0);

        verify(interactionHook, times(1)).sendMessage("I have a bad understanding of time, let me know what the date is so I can greet you appropriately");
    }

    @Test
    public void testWithInvalidDateFormat(){
        OptionMapping messageOption = Mockito.mock(OptionMapping.class);
        when(messageOption.getAsString()).thenReturn("19/05/2005");
        when(event.getOption("date")).thenReturn(messageOption);

        UserDto requestingUserDto = new UserDto(1L, 1L, true, true, true, true, 0L, null); // You can set the user as needed

        // Mock the event to return the MESSAGE option
        when(event.getOptions()).thenReturn(List.of(messageOption));
        when(event.getOption(anyString())).thenReturn(messageOption);
        when(guild.getId()).thenReturn("1");
        when(configService.getConfigByName("DATEFORMAT", "1")).thenReturn(new ConfigDto("DATEFORMAT", "yyyy/MM/dd", "1"));

        command.handle(new CommandContext(event), requestingUserDto, 0);

        verify(interactionHook, times(1)).sendMessageFormat("I don't recognise the format of the date you gave me, please use this format %s", "yyyy/MM/dd");
    }
}