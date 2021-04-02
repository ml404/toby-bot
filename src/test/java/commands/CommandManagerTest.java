package commands;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.ICommand;
import toby.command.commands.*;
import toby.command.commands.music.*;
import toby.jpa.service.impl.BrotherServiceImpl;
import toby.jpa.service.impl.ConfigServiceImpl;
import toby.managers.CommandManager;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CommandManagerTest {

    @Mock
    ConfigServiceImpl configService;
    @Mock
    BrotherServiceImpl brotherService;


    @Test
    public void testCommandManagerFindsAllCommands() {
        CommandManager commandManager = new CommandManager(configService, brotherService);

        List<Class<? extends ICommand>> availableCommands = Arrays.asList(HelpCommand.class,
                SetPrefixCommand.class,
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
                ShuffleCommand.class);

        assertEquals(availableCommands, commandManager.getCommands().stream().map(ICommand::getClass).collect(Collectors.toList()));
        assertEquals(25, commandManager.getCommands().size());
    }
}
