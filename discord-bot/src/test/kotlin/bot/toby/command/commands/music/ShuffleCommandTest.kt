package bot.toby.command.commands.music

import bot.toby.command.CommandContextImpl
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.commands.music.player.ShuffleCommand
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
        unmockkAll()
    }

    @Test
    fun testShuffleCommand_withValidQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContextImpl(event)
        every { MusicCommandTest.mockAudioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption("skip") } returns optionMapping
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
        verify(exactly = 1) { event.hook.sendMessage("The queue has been shuffled 🦧") }
    }

    @Test
    fun testShuffleCommand_withNoQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContextImpl(event)
        every { MusicCommandTest.mockAudioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val optionMapping = mockk<OptionMapping>()
        every { event.getOption("skip") } returns optionMapping
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
        verify(exactly = 1) { event.hook.sendMessage("I can't shuffle a queue that doesn't exist") }
    }
}
