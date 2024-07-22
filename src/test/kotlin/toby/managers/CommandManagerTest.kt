package toby.managers

import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.ICommand
import toby.command.commands.dnd.DnDCommand
import toby.command.commands.dnd.InitiativeCommand
import toby.command.commands.fetch.DbdRandomKillerCommand
import toby.command.commands.fetch.Kf2RandomMapCommand
import toby.command.commands.fetch.MemeCommand
import toby.command.commands.misc.*
import toby.command.commands.moderation.*
import toby.command.commands.music.*
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.service.*
import toby.lavaplayer.PlayerManager

class CommandManagerTest {

    lateinit var configService: IConfigService
    lateinit var brotherService: IBrotherService
    lateinit var userService: IUserService
    lateinit var musicFileService: IMusicFileService
    lateinit var excuseService: IExcuseService
    lateinit var commandManager: CommandManager

    @BeforeEach
    fun openMocks() {
        configService = mockk()
        brotherService = mockk()
        userService = mockk()
        musicFileService = mockk()
        excuseService = mockk()
        commandManager = CommandManager(configService, brotherService, userService, musicFileService, excuseService)
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

        Assertions.assertTrue(availableCommands.containsAll(commandManager.allCommands.map { it.javaClass }.toList()))
        Assertions.assertEquals(37, commandManager.allCommands.size)
        Assertions.assertEquals(37, commandManager.allSlashCommands.size)
    }

    @Test
    fun `test handle SlashCommandInteractionEvent`() {

        val mockGuild = mockk<Guild> {
            every { id } returns "1"
            every { idLong } returns 1L
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
            }
            every { member } returns mockk {
                every { isOwner } returns true
            }
            every { deferReply().queue() } just Runs
            every { hook } returns mockHook
        }

        val command = mockk<ICommand> {
            every { name } returns "8ball"
            every { handle(any(), any(), any()) } just Runs
        }
        every { configService.getConfigByName(any(), any()) } returns ConfigDto("test", "1")
        every { userService.getUserById(any(), any()) } returns mockk(relaxed = true)
        every { userService.updateUser(any()) } returns mockk(relaxed = true)

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
