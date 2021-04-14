package commands;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.ICommand;
import toby.command.commands.fetch.MemeCommand;
import toby.command.commands.misc.*;
import toby.command.commands.moderation.*;
import toby.command.commands.music.*;
import toby.jpa.service.impl.BrotherServiceImpl;
import toby.jpa.service.impl.ConfigServiceImpl;
import toby.jpa.service.impl.UserServiceImpl;
import toby.managers.CommandManager;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandManagerTest {

    @Mock
    ConfigServiceImpl configService;
    @Mock
    BrotherServiceImpl brotherService;

    @Mock
    UserServiceImpl userService;

    @Mock
    EventWaiter waiter;

    @Test
    public void testCommandManagerFindsAllCommands() {
        CommandManager commandManager = new CommandManager(configService, brotherService, userService, waiter);

        List<Class<? extends ICommand>> availableCommands = Arrays.asList(HelpCommand.class,
                SetConfigCommand.class,
                KickCommand.class,
                MoveCommand.class,
                RollCommand.class,
                MemeCommand.class,
                HelloThereCommand.class,
                BrotherCommand.class,
                ChCommand.class,
                ShhCommand.class,
                TalkCommand.class,
                PollCommand.class,
                JoinCommand.class,
                LeaveCommand.class,
                PlayCommand.class,
                NowDigOnThisCommand.class,
                SetVolumeCommand.class,
                PauseCommand.class,
                ResumeCommand.class,
                LoopCommand.class,
                StopCommand.class,
                SkipCommand.class,
                NowPlayingCommand.class,
                QueueCommand.class,
                ShuffleCommand.class,
                AdjustUserCommand.class,
                EventWaiterCommand.class);

        assertTrue(availableCommands.containsAll(commandManager.getAllCommands().stream().map(ICommand::getClass).collect(Collectors.toList())));
        assertEquals(27, commandManager.getAllCommands().size());
    }
}
