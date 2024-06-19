package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import java.util.concurrent.ArrayBlockingQueue

internal class ShuffleCommandTest : MusicCommandTest {
    lateinit var shuffleCommand: ShuffleCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        shuffleCommand = ShuffleCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearMocks(CommandTest.event, MusicCommandTest.audioPlayer, MusicCommandTest.playerManager, CommandTest.interactionHook)
    }

    @Test
    fun testShuffleCommand_withValidQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val optionMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("skip") } returns optionMapping
        every { optionMapping.asInt } returns 2

        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(2)
        val track2 = mockk<AudioTrack>()
        every { track2.info } returns AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri")
        every { track2.duration } returns 1000L

        queue.add(MusicCommandTest.track)
        queue.add(track2)

        every { MusicCommandTest.trackScheduler.queue } returns queue
        every { MusicCommandTest.track.userData } returns 1

        // Act
        shuffleCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("The queue has been shuffled 🦧") }
    }

    @Test
    fun testShuffleCommand_withNoQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val optionMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("skip") } returns optionMapping
        every { optionMapping.asInt } returns 2

        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(1)
        val track2 = mockk<AudioTrack>()
        every { track2.info } returns AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri")
        every { track2.duration } returns 1000L

        every { MusicCommandTest.trackScheduler.queue } returns queue
        every { MusicCommandTest.track.userData } returns 1

        // Act
        shuffleCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("I can't shuffle a queue that doesn't exist") }
    }
}
