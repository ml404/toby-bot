package toby.command.commands.misc;

import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import toby.command.CommandContext;
import toby.command.CommandTest;
import toby.jpa.dto.ExcuseDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IExcuseService;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ExcuseCommandTest implements CommandTest {

    private ExcuseCommand excuseCommand;

    @Mock
    private IExcuseService excuseService;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        excuseService = mock(IExcuseService.class);
        excuseCommand = new ExcuseCommand(excuseService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    public void getARandomApprovedExcuse_WhenNoOptionsUsed() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;

        // Mock the behavior of the excuseService when listing approved guild excuses
        ExcuseDto excuseDto = new ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true);
        List<ExcuseDto> excuseDtos = List.of(
                excuseDto
        );
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOptions()).thenReturn(Collections.emptyList());
        when(event.getOption("action")).thenReturn(optionMapping);
        when(optionMapping.getAsString()).thenReturn("all");
        when(excuseService.listApprovedGuildExcuses(Mockito.anyLong())).thenReturn(excuseDtos);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        verify(interactionHook, times(1)).sendMessageFormat(eq("Excuse #%d: '%s' - %s."), eq(excuseDto.getId()), eq(excuseDto.getExcuse()), eq(excuseDto.getAuthor()));
    }

    @Test
    public void listAllApprovedExcuses_WithValidApprovedOnes() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;

        // Mock the behavior of the excuseService when listing approved guild excuses
        List<ExcuseDto> excuseDtos = List.of(
                new ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true),
                new ExcuseDto(2, 1L, "TestAuthor", "Excuse 2", true),
                new ExcuseDto(3, 1L, "TestAuthor", "Excuse 3", true)
        );
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOptions()).thenReturn(List.of(optionMapping));
        when(event.getOption("action")).thenReturn(optionMapping);
        when(optionMapping.getAsString()).thenReturn("all");
        when(excuseService.listApprovedGuildExcuses(Mockito.anyLong())).thenReturn(excuseDtos);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        verify(interactionHook, times(1)).sendMessage(anyString());
    }

    @Test
    public void listAllApprovedExcuses_WithNoValidApprovedOnes() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;

        // Mock the behavior of the excuseService when listing approved guild excuses
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOptions()).thenReturn(Collections.emptyList());
        when(event.getOption("action")).thenReturn(optionMapping);
        when(optionMapping.getAsString()).thenReturn("all");
        when(excuseService.listApprovedGuildExcuses(Mockito.anyLong())).thenReturn(Collections.emptyList());

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        verify(interactionHook, times(1)).sendMessage(eq("There are no approved excuses, consider submitting some."));
    }

    @Test
    public void createNewExcuse() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;
        ExcuseDto excuseToCreate = new ExcuseDto(1, 1L, "UserName", "Excuse 1", false);
        OptionMapping excuseMapping = mock(OptionMapping.class);
        when(event.getOption("excuse")).thenReturn(excuseMapping);
        when(event.getOptions()).thenReturn(List.of(excuseMapping));
        when(excuseMapping.getAsString()).thenReturn("Excuse 1");
        when(excuseService.listAllGuildExcuses(1L)).thenReturn(Collections.emptyList());
        when(excuseService.createNewExcuse(any(ExcuseDto.class))).thenReturn(excuseToCreate);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        //see if excuse exists
        verify(excuseService, times(1)).listAllGuildExcuses(1L);
        // it doesn't, so create it
        verify(excuseService, times(1)).createNewExcuse(eq(new ExcuseDto(null, 1L, "UserName", "Excuse 1", false)));
        // send a message that your excuse exists in pending form
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq(excuseToCreate.getExcuse()), eq(excuseToCreate.getAuthor()), eq(excuseToCreate.getId()));
        
    }

    @Test
    public void createNewExcuse_thatExists_throwsError() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;

        ExcuseDto excuseToCreate = new ExcuseDto(1, 1L, "UserName", "Excuse 1", false);
        List<ExcuseDto> excuseDtos = List.of(
                excuseToCreate
        );
        OptionMapping excuseMapping = mock(OptionMapping.class);
        when(event.getOption("excuse")).thenReturn(excuseMapping);
        when(event.getOptions()).thenReturn(List.of(excuseMapping));
        when(excuseMapping.getAsString()).thenReturn("Excuse 1");
        when(excuseService.listAllGuildExcuses(1L)).thenReturn(excuseDtos);
        when(excuseService.createNewExcuse(any(ExcuseDto.class))).thenReturn(excuseToCreate);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        //see if excuse exists
        verify(excuseService, times(1)).listAllGuildExcuses(1L);
        // it does, so don't create it
        verify(excuseService, times(0)).createNewExcuse(eq(new ExcuseDto(null, 1L, "UserName", "Excuse 1", false)));
        // send a message that your excuse exists in pending form
        verify(interactionHook, times(1)).sendMessage(eq("I've heard that one before, keep up."));
        
    }

    @Test
    public void approvePendingExcuse_asSuperUser() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;
        ExcuseDto preUpdatedExcuse = new ExcuseDto(1, 1L, "UserName", "Excuse 1", false);
        ExcuseDto excuseToBeReturnedByUpdate = new ExcuseDto(1, 1L, "UserName", "Excuse 1", true);
        OptionMapping excuseMapping = mock(OptionMapping.class);
        OptionMapping actionMapping = mock(OptionMapping.class);
        when(event.getOption("id")).thenReturn(excuseMapping);
        when(event.getOption("action")).thenReturn(actionMapping);
        when(event.getOptions()).thenReturn(List.of(excuseMapping));
        when(userDto.isSuperUser()).thenReturn(true);
        when(excuseMapping.getAsInt()).thenReturn(1);
        when(actionMapping.getAsString()).thenReturn("approve");
        when(excuseService.getExcuseById(1)).thenReturn(preUpdatedExcuse);
        when(excuseService.updateExcuse(any(ExcuseDto.class))).thenReturn(excuseToBeReturnedByUpdate);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        //see if excuse exists
        verify(excuseService, times(1)).getExcuseById(eq(excuseToBeReturnedByUpdate.getId()));
        // it doesn't, so create it
        verify(excuseService, times(1)).updateExcuse(eq(new ExcuseDto(1, 1L, "UserName", "Excuse 1", true)));
        // send a message that your excuse exists in pending form
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq(excuseToBeReturnedByUpdate.getExcuse()));
    }

    @Test
    public void approvePendingExcuse_asNonAuthorisedUser() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;
        ExcuseDto excuseToCreate = new ExcuseDto(1, 1L, "UserName", "Excuse 1", true);
        OptionMapping excuseMapping = mock(OptionMapping.class);
        OptionMapping actionMapping = mock(OptionMapping.class);
        when(event.getOption("id")).thenReturn(excuseMapping);
        when(event.getOption("action")).thenReturn(actionMapping);
        when(event.getOptions()).thenReturn(List.of(excuseMapping));
        when(excuseMapping.getAsInt()).thenReturn(1);
        when(actionMapping.getAsString()).thenReturn("approve");
        when(excuseService.getExcuseById(1)).thenReturn(excuseToCreate);
        when(excuseService.updateExcuse(any(ExcuseDto.class))).thenReturn(excuseToCreate);
        when(guild.getOwner()).thenReturn(member);
        when(member.getEffectiveName()).thenReturn("Effective Name");
        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        // send a message to say you're not authorised
        verify(interactionHook, times(1)).sendMessageFormat(eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"));
        //don't do lookups
        verify(excuseService, times(0)).getExcuseById(eq(excuseToCreate.getId()));
        //don't approve
        verify(excuseService, times(0)).updateExcuse(eq(new ExcuseDto(1, 1L, "UserName", "Excuse 1", true)));
    }


    @Test
    public void listAllPendingExcuses_WithValidPendingOnes() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;

        // Mock the behavior of the excuseService when listing approved guild excuses
        List<ExcuseDto> excuseDtos = List.of(
                new ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true),
                new ExcuseDto(2, 1L, "TestAuthor", "Excuse 2", true),
                new ExcuseDto(3, 1L, "TestAuthor", "Excuse 3", true)
        );
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOptions()).thenReturn(List.of(optionMapping));
        when(event.getOption("action")).thenReturn(optionMapping);
        when(optionMapping.getAsString()).thenReturn("pending");
        when(excuseService.listPendingGuildExcuses(Mockito.anyLong())).thenReturn(excuseDtos);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        verify(interactionHook, times(1)).sendMessage(anyString());
    }

    @Test
    public void listAllPendingExcuses_WithNoValidPendingOnes() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;

        // Mock the behavior of the excuseService when listing approved guild excuses
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOptions()).thenReturn(List.of(optionMapping));
        when(event.getOption("action")).thenReturn(optionMapping);
        when(optionMapping.getAsString()).thenReturn("pending");
        when(excuseService.listPendingGuildExcuses(Mockito.anyLong())).thenReturn(Collections.emptyList());

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        verify(interactionHook, times(1)).sendMessage(eq("There are no excuses pending approval, consider submitting some."));
    }

    @Test
    public void deleteExcuse_asValidUser() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;
        ExcuseDto preUpdatedExcuse = new ExcuseDto(1, 1L, "UserName", "Excuse 1", false);
        ExcuseDto excuseToBeReturnedByUpdate = new ExcuseDto(1, 1L, "UserName", "Excuse 1", true);
        OptionMapping excuseMapping = mock(OptionMapping.class);
        OptionMapping actionMapping = mock(OptionMapping.class);
        when(event.getOption("id")).thenReturn(excuseMapping);
        when(event.getOption("action")).thenReturn(actionMapping);
        when(event.getOptions()).thenReturn(List.of(excuseMapping));
        when(userDto.isSuperUser()).thenReturn(true);
        when(excuseMapping.getAsInt()).thenReturn(1);
        when(actionMapping.getAsString()).thenReturn("delete");
        when(excuseService.getExcuseById(1)).thenReturn(preUpdatedExcuse);
        when(excuseService.updateExcuse(any(ExcuseDto.class))).thenReturn(excuseToBeReturnedByUpdate);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        // deleteById
        verify(excuseService, times(1)).deleteExcuseById(eq(1));
        // post update about deleting entry
        verify(interactionHook, times(1)).sendMessageFormat(anyString(), eq(excuseToBeReturnedByUpdate.getId()));
        
    }

    @Test
    public void deleteExcuse_asInvalidUser() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;
        ExcuseDto preUpdatedExcuse = new ExcuseDto(1, 1L, "UserName", "Excuse 1", false);
        ExcuseDto excuseToBeReturnedByUpdate = new ExcuseDto(1, 1L, "UserName", "Excuse 1", true);
        OptionMapping excuseMapping = mock(OptionMapping.class);
        OptionMapping actionMapping = mock(OptionMapping.class);
        when(event.getOption("id")).thenReturn(excuseMapping);
        when(event.getOption("action")).thenReturn(actionMapping);
        when(event.getOptions()).thenReturn(List.of(excuseMapping));
        when(userDto.isSuperUser()).thenReturn(false);
        when(excuseMapping.getAsInt()).thenReturn(1);
        when(actionMapping.getAsString()).thenReturn("delete");
        when(guild.getOwner()).thenReturn(member);
        when(member.getEffectiveName()).thenReturn("Effective Name");
        when(excuseService.getExcuseById(1)).thenReturn(preUpdatedExcuse);
        when(excuseService.updateExcuse(any(ExcuseDto.class))).thenReturn(excuseToBeReturnedByUpdate);

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        // deleteById
        verify(excuseService, times(0)).deleteExcuseById(eq(1));
        // post error message
        verify(interactionHook, times(1)).sendMessageFormat(eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"));
    }

    @Test
    public void testHandle_InvalidAction() {
        // Test for handling an invalid action case
    }
}