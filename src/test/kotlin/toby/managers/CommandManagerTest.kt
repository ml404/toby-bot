package toby.managers

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import toby.command.ICommand
import toby.command.commands.dnd.DnDCommand
import toby.command.commands.dnd.InitiativeCommand
import toby.command.commands.fetch.DbdRandomKillerCommand
import toby.command.commands.fetch.Kf2RandomMapCommand
import toby.command.commands.fetch.MemeCommand
import toby.command.commands.misc.*
import toby.command.commands.moderation.*
import toby.command.commands.music.*
import toby.jpa.service.IExcuseService
import toby.jpa.service.impl.BrotherServiceImpl
import toby.jpa.service.impl.ConfigServiceImpl
import toby.jpa.service.impl.MusicFileServiceImpl
import toby.jpa.service.impl.UserServiceImpl

class CommandManagerTest {
    @Mock
    var configService: ConfigServiceImpl? = null

    @Mock
    var brotherService: BrotherServiceImpl? = null

    @Mock
    var userService: UserServiceImpl? = null

    @Mock
    var musicFileService: MusicFileServiceImpl? = null

    @Mock
    var excuseService: IExcuseService? = null


    private var closeable: AutoCloseable? = null


    @BeforeEach
    fun openMocks() {
        closeable = MockitoAnnotations.openMocks(this)
    }

    @AfterEach
    @Throws(Exception::class)
    fun releaseMocks() {
        closeable!!.close()
    }

    @Test
    fun testCommandManagerFindsAllCommands() {
        val commandManager =
            CommandManager(configService!!, brotherService!!, userService!!, musicFileService!!, excuseService!!)

        val availableCommands = listOf(
            HelpCommand::class.java,
            SetConfigCommand::class.java,
            KickCommand::class.java,
            MoveCommand::class.java,
            RollCommand::class.java,
            MemeCommand::class.java,
            DnDCommand::class.java,
            InitiativeCommand::class.java,
            HelloThereCommand::class.java,
            BrotherCommand::class.java,
            ChCommand::class.java,
            ShhCommand::class.java,
            TalkCommand::class.java,
            PollCommand::class.java,
            JoinCommand::class.java,
            LeaveCommand::class.java,
            PlayCommand::class.java,
            NowDigOnThisCommand::class.java,
            SetVolumeCommand::class.java,
            PauseCommand::class.java,
            ResumeCommand::class.java,
            LoopCommand::class.java,
            StopCommand::class.java,
            SkipCommand::class.java,
            NowPlayingCommand::class.java,
            QueueCommand::class.java,
            ShuffleCommand::class.java,
            AdjustUserCommand::class.java,
            IntroSongCommand::class.java,
            UserInfoCommand::class.java,
            RandomCommand::class.java,
            Kf2RandomMapCommand::class.java,
            DbdRandomKillerCommand::class.java,
            ExcuseCommand::class.java,
            SocialCreditCommand::class.java,
            TeamCommand::class.java,
            EightBallCommand::class.java
        )

        Assertions.assertTrue(
            availableCommands.containsAll(
                commandManager.allCommands.stream().map { obj: ICommand -> obj.javaClass }
                    .toList()))
        Assertions.assertEquals(37, commandManager.allCommands.size)
        Assertions.assertEquals(37, commandManager.allSlashCommands.size)
    }
}
