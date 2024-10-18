package bot.toby.managers

import bot.Application
import bot.configuration.*
import bot.toby.command.commands.dnd.DnDSearchCommand
import bot.toby.command.commands.dnd.InitiativeCommand
import bot.toby.command.commands.dnd.RollCommand
import bot.toby.command.commands.fetch.DbdRandomKillerCommand
import bot.toby.command.commands.fetch.Kf2RandomMapCommand
import bot.toby.command.commands.fetch.MemeCommand
import bot.toby.command.commands.misc.*
import bot.toby.command.commands.moderation.*
import bot.toby.command.commands.music.channel.JoinCommand
import bot.toby.command.commands.music.channel.LeaveCommand
import bot.toby.command.commands.music.intro.EditIntroCommand
import bot.toby.command.commands.music.intro.SetIntroCommand
import bot.toby.command.commands.music.player.*
import bot.toby.helpers.*
import bot.toby.lavaplayer.PlayerManager
import common.configuration.TestCachingConfig
import core.command.Command
import database.configuration.TestDatabaseConfig
import database.dto.ConfigDto
import database.service.*
import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [
        Application::class,
        TestAppConfig::class,
        TestBotConfig::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class
    ]
)
@ActiveProfiles("test")
class CommandManagerTest {

    lateinit var configService: ConfigService
    lateinit var userDtoHelper: UserDtoHelper
    private lateinit var commandManager: DefaultCommandManager

    @Autowired
    lateinit var commands: List<Command>

    @BeforeEach
    fun openMocks() {
        configService = mockk()
        userDtoHelper = mockk()
        commandManager = DefaultCommandManager(configService, userDtoHelper, commands)
        mockkStatic(PlayerManager::class)
        mockkObject(MusicPlayerHelper)
    }

    @AfterEach
    @Throws(Exception::class)
    fun releaseMocks() {
        unmockkAll()
    }

    @Test
    fun testCommandManagerFindsAllCommands() {
        val availableCommands = listOf(
            HelpCommand::class.java,
            SetConfigCommand::class.java,
            KickCommand::class.java,
            MoveCommand::class.java,
            RollCommand::class.java,
            MemeCommand::class.java,
            DnDSearchCommand::class.java,
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
            SetIntroCommand::class.java,
            EditIntroCommand::class.java,
            UserInfoCommand::class.java,
            RandomCommand::class.java,
            Kf2RandomMapCommand::class.java,
            DbdRandomKillerCommand::class.java,
            ExcuseCommand::class.java,
            SocialCreditCommand::class.java,
            TeamCommand::class.java,
            EightBallCommand::class.java
        )

        Assertions.assertTrue(availableCommands.containsAll(commandManager.allCommands.map { it.javaClass }.toList()))
        Assertions.assertEquals(38, commandManager.allCommands.size)
        Assertions.assertEquals(38, commandManager.allSlashCommands.size)
    }

    @Test
    fun `test handle SlashCommandInteractionEvent`() {

        val mockGuild = mockk<Guild> {
            every { id } returns "1"
            every { idLong } returns 1L
            every { name } returns "guildName"
            every { audioManager } returns mockk(relaxed = true) {

            }
        }

        val mockHook = mockk<InteractionHook> {
            every { deleteOriginal() } returns mockk {
                every { queue() } just Runs
            }
            every { sendMessage(any<String>()) } returns mockk {
                every { queue(any()) } just Runs
            }
        }

        val mockChannel = mockk<MessageChannelUnion> {
            every { sendTyping().queue() } just Runs
        }
        val event = mockk<SlashCommandInteractionEvent> {
            every { guild } returns mockGuild
            every { channel } returns mockChannel
            every { name } returns "8ball"
            every { user } returns mockk {
                every { idLong } returns 1
                every { id } returns "1"
            }
            every { member } returns mockk {
                every { isOwner } returns true
                every { effectiveName } returns "effectiveName"
                every { idLong } returns 123L
                every { id } returns "123"
                every { user } returns mockk {
                    every { user.idLong } returns 123L
                }
            }
            every { deferReply().queue() } just Runs
            every { hook } returns mockHook
        }

        val command = mockk<Command> {
            every { name } returns "8ball"
            every { handle(any(), any(), any()) } just Runs
        }
        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")
        every { userDtoHelper.updateUser(any()) } returns mockk(relaxed = true)
        every { userDtoHelper.calculateUserDto(any(), any(), any()) } returns mockk(relaxed = true)

        // Real instance of CommandManager is used, so you can't mock it directly
        // Instead, spy on the CommandManager and mock its getCommand method
        val commandManagerSpy = spyk(commandManager)

        every { commandManagerSpy.getCommand("8ball") } returns command

        // Call handle method of commandManager with the event
        commandManagerSpy.handle(event)

        verify { mockChannel.sendTyping().queue() }
        verify { command.handle(any(), any(), any()) }
    }
}
