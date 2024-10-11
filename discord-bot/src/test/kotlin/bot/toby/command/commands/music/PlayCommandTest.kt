package bot.toby.command.commands.music

import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import bot.toby.command.commands.music.MusicCommandTest.Companion.playerManager
import bot.toby.command.commands.music.MusicCommandTest.Companion.track
import bot.toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import bot.toby.command.commands.music.player.PlayCommand
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue

internal class PlayCommandTest : MusicCommandTest {
    lateinit var playCommand: PlayCommand

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
    fun test_playCommand_withValidArguments() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false


        val linkOptionMapping = mockk<OptionMapping>()
        val typeOptionMapping = mockk<OptionMapping>()
        val volumeOptionMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("type") } returns typeOptionMapping
        every { CommandTest.event.getOption("link") } returns linkOptionMapping
        every { CommandTest.event.getOption("volume") } returns volumeOptionMapping
        every { typeOptionMapping.asString } returns "link"
        every { linkOptionMapping.asString } returns "www.testlink.com"
        every { volumeOptionMapping.asInt } returns 20

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
}
