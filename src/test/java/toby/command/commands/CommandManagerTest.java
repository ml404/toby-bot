package toby.command.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import toby.command.ICommand;
import toby.command.commands.fetch.DbdRandomKillerCommand;
import toby.command.commands.fetch.Kf2RandomMapCommand;
import toby.command.commands.fetch.MemeCommand;
import toby.command.commands.misc.*;
import toby.command.commands.moderation.*;
import toby.command.commands.music.*;
import toby.jpa.service.IExcuseService;
import toby.jpa.service.impl.BrotherServiceImpl;
import toby.jpa.service.impl.ConfigServiceImpl;
import toby.jpa.service.impl.MusicFileServiceImpl;
import toby.jpa.service.impl.UserServiceImpl;
import toby.managers.CommandManager;

import java.util.Arrays;
import java.util.List;

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
    MusicFileServiceImpl musicFileService;

    @Mock
    IExcuseService excuseService;


    private AutoCloseable closeable;


    @BeforeEach
    public void openMocks() {
        closeable = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    public void releaseMocks() throws Exception {
        closeable.close();
    }

    @Test
    public void testCommandManagerFindsAllCommands() {
        CommandManager commandManager = new CommandManager(configService, brotherService, userService, musicFileService, excuseService);

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
//                PollCommand.class,
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
                IntroSongCommand.class,
//                EventWaiterCommand.class,
                UserInfoCommand.class,
                RandomCommand.class,
                Kf2RandomMapCommand.class,
                DbdRandomKillerCommand.class,
                ExcuseCommand.class,
                SocialCreditCommand.class,
                TeamCommand.class,
                EightBallCommand.class
                );

        assertTrue(availableCommands.containsAll(commandManager.getAllCommands().stream().map(ICommand::getClass).toList()));
        assertEquals(34, commandManager.getAllCommands().size());
        assertEquals(34, commandManager.getAllSlashCommands().size());
    }
}
