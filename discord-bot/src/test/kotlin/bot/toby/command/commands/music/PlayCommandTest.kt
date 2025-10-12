package bot.toby.command.commands.music

import bot.toby.command.CommandTest
import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import bot.toby.command.commands.music.MusicCommandTest.Companion.playerManager
import bot.toby.command.commands.music.MusicCommandTest.Companion.track
import bot.toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import bot.toby.command.commands.music.player.PlayCommand
import bot.toby.helpers.MusicPlayerHelper
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue

internal class PlayCommandTest : MusicCommandTest {

    private lateinit var playCommand: PlayCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        playCommand = PlayCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun test_playCommand_linkSubcommand_withValidArguments() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = DefaultCommandContext(CommandTest.event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        // Mock event options for subcommand "link"
        val linkOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()
        val startOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.subcommandName } returns "link"
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { CommandTest.event.getOption("volume") } returns volumeOptionMapping
        every { CommandTest.event.getOption("start") } returns startOptionMapping

        every { linkOptionMapping.asString } returns "www.testlink.com"
        every { volumeOptionMapping.asInt } returns 20
        every { startOptionMapping.asLong } returns 0L

        val queue = ArrayBlockingQueue<AudioTrack>(2)
        val track2 = mockk<AudioTrack>()
        every { track2.info } returns AudioTrackInfo(
            "Another Title",
            "Another Author",
            1000L,
            "identifier",
            true,
            "uri"
        )
        every { track2.duration } returns 1000L
        queue.add(track)
        queue.add(track2)
        every { trackScheduler.queue } returns queue
        every { track.userData } returns 1

        // Act
        playCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            playerManager.loadAndPlay(
                eq(CommandTest.guild),
                eq(CommandTest.event),
                eq("www.testlink.com"),
                eq(true),
                eq(0),
                eq(0L),
                eq(20)
            )
        }
    }

    @Test
    fun test_playCommand_introSubcommand_playsUserIntro() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = DefaultCommandContext(CommandTest.event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        every { CommandTest.event.subcommandName } returns "intro"
        every { CommandTest.event.getOption("start") } returns null
        every { CommandTest.event.getOption("volume") } returns null

        mockkObject(MusicPlayerHelper)
        every {
            MusicPlayerHelper.playUserIntro(
                any(), any(), any(), any(), any(), any()
            )
        } just Runs

        // Act
        playCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            5
        )

        // Assert
        verify(exactly = 1) {
            MusicPlayerHelper.playUserIntro(
                CommandTest.requestingUserDto,
                CommandTest.guild,
                CommandTest.event,
                5,
                0L,
                CommandTest.member
            )
        }
        verify(exactly = 0) { playerManager.loadAndPlay(any(), any(), any(), any(), any(), any(), any()) }

        unmockkObject(MusicPlayerHelper)
    }
}
