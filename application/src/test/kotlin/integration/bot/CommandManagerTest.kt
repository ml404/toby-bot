package integration.bot

import app.Application
import bot.configuration.*
import bot.toby.command.commands.dnd.DnDSearchCommand
import bot.toby.command.commands.dnd.RollCommand
import bot.toby.command.commands.game.casino.baccarat.BaccaratCommand
import bot.toby.command.commands.game.casino.blackjack.BlackjackCommand
import bot.toby.command.commands.game.casino.casinoholdem.CasinoHoldemCommand
import bot.toby.command.commands.game.casino.coinflip.CoinflipCommand
import bot.toby.command.commands.game.pvp.connect4.Connect4Command
import bot.toby.command.commands.game.casino.dice.DiceCommand
import bot.toby.command.commands.game.pvp.duel.DuelCommand
import bot.toby.command.commands.game.casino.highlow.HighlowCommand
import bot.toby.command.commands.game.casino.horseracing.HorseRacingCommand
import bot.toby.command.commands.game.casino.keno.KenoCommand
import bot.toby.command.commands.game.lottery.LotteryCommand
import bot.toby.command.commands.game.casino.plinko.PlinkoCommand
import bot.toby.command.commands.game.casino.poker.PokerCommand
import bot.toby.command.commands.economy.PriceAlertCommand
import bot.toby.command.commands.game.casino.roulette.RouletteCommand
import bot.toby.command.commands.game.pvp.rps.RpsCommand
import bot.toby.command.commands.game.casino.scratch.ScratchCommand
import bot.toby.command.commands.game.casino.slots.SlotsCommand
import bot.toby.command.commands.game.pvp.tictactoe.TicTacToeCommand
import bot.toby.command.commands.economy.TipCommand
import bot.toby.command.commands.economy.TitleCommand
import bot.toby.command.commands.economy.TobyCoinCommand
import bot.toby.command.commands.game.casino.wheeloffortune.WheelOfFortuneCommand
import bot.toby.command.commands.fetch.MemeCommand
import bot.toby.command.commands.misc.*
import bot.toby.command.commands.moderation.*
import bot.toby.command.commands.mtg.CardCommand
import bot.toby.command.commands.mtg.CubeCommand
import bot.toby.command.commands.mtg.DeckCommand
import bot.toby.command.commands.mtg.MtgReferenceCommand
import bot.toby.command.commands.mtg.PriceWatchCommand
import bot.toby.command.commands.music.channel.JoinCommand
import bot.toby.command.commands.music.channel.LeaveCommand
import bot.toby.command.commands.music.intro.DeleteIntroCommand
import bot.toby.command.commands.music.intro.EditIntroCommand
import bot.toby.command.commands.music.intro.SetIntroCommand
import bot.toby.command.commands.music.player.*
import bot.toby.helpers.*
import bot.toby.install.InstallCommand
import bot.toby.lavaplayer.PlayerManager
import bot.toby.managers.DefaultCommandManager
import common.configuration.TestCachingConfig
import core.command.Command
import database.configuration.TestDatabaseConfig
import database.dto.guild.ConfigDto
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
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService

@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@ActiveProfiles("test")
class CommandManagerTest {

    lateinit var configService: ConfigService
    lateinit var userDtoHelper: UserDtoHelper
    lateinit var awardService: SocialCreditAwardService
    private lateinit var commandManager: DefaultCommandManager

    @Autowired
    lateinit var commands: List<Command>

    @BeforeEach
    fun openMocks() {
        configService = mockk()
        userDtoHelper = mockk()
        awardService = mockk(relaxed = true)
        commandManager = DefaultCommandManager(configService, userDtoHelper, awardService, commands)
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
            BrotherCommand::class.java,
            BanCommand::class.java,
            UnbanCommand::class.java,
            TimeoutCommand::class.java,
            UntimeoutCommand::class.java,
            PurgeCommand::class.java,
            LockCommand::class.java,
            UnlockCommand::class.java,
            SlowmodeCommand::class.java,
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
            DeleteIntroCommand::class.java,
            UserInfoCommand::class.java,
            LevelCommand::class.java,
            RandomCommand::class.java,
            ExcuseCommand::class.java,
            SocialCreditCommand::class.java,
            JackpotAdminCommand::class.java,
            TeamCommand::class.java,
            EightBallCommand::class.java,
            TitleCommand::class.java,
            TobyCoinCommand::class.java,
            PriceAlertCommand::class.java,
            SlotsCommand::class.java,
            CoinflipCommand::class.java,
            DiceCommand::class.java,
            HighlowCommand::class.java,
            ScratchCommand::class.java,
            ActivityCommand::class.java,
            TipCommand::class.java,
            DuelCommand::class.java,
            PokerCommand::class.java,
            BlackjackCommand::class.java,
            BaccaratCommand::class.java,
            KenoCommand::class.java,
            CasinoHoldemCommand::class.java,
            RouletteCommand::class.java,
            LotteryCommand::class.java,
            PlinkoCommand::class.java,
            WheelOfFortuneCommand::class.java,
            HorseRacingCommand::class.java,
            SupportCommand::class.java,
            DailyCommand::class.java,
            AchievementsCommand::class.java,
            NotifyCommand::class.java,
            InstallCommand::class.java,
            WelcomeCommand::class.java,
            ProfileCommand::class.java,
            RpsCommand::class.java,
            TicTacToeCommand::class.java,
            Connect4Command::class.java,
            CubeCommand::class.java,
            CardCommand::class.java,
            DeckCommand::class.java,
            MtgReferenceCommand::class.java,
            PriceWatchCommand::class.java,
        )

        Assertions.assertTrue(availableCommands.containsAll(commandManager.allCommands.map { it.javaClass }.toList()))
        Assertions.assertEquals(availableCommands.size, commandManager.allCommands.size)
        Assertions.assertEquals(availableCommands.size, commandManager.allSlashCommands.size)
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
            every { deferReply(any<Boolean>()).queue() } just Runs
            every { hook } returns mockHook
        }

        val command = mockk<Command> {
            every { name } returns "8ball"
            every { defersReply } returns true
            every { ephemeral } returns false
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
