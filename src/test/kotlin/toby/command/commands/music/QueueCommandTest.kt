package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import java.util.concurrent.ArrayBlockingQueue

internal class QueueCommandTest : MusicCommandTest {
    lateinit var queueCommand: QueueCommand

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        queueCommand = QueueCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearMocks(CommandTest.event, MusicCommandTest.audioPlayer, MusicCommandTest.playerManager, MusicCommandTest.trackScheduler, MusicCommandTest.track, CommandTest.requestingUserDto)
    }

    @Test
    fun testQueue_WithNoTrackInTheQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false

        // Act
        queueCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.event.hook.sendMessage("The queue is currently empty") }
    }

    @Test
    fun testQueue_WithOneTrackInTheQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(1)
        queue.add(MusicCommandTest.track)
        every { MusicCommandTest.trackScheduler.queue } returns queue

        // Act
        queueCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.event.hook.sendMessage("**Current Queue:**\n") }
        verifyOrder {
            webhookMessageCreateAction.addContent("#")
            webhookMessageCreateAction.addContent("1")
            webhookMessageCreateAction.addContent(" `")
            webhookMessageCreateAction.addContent("Title")
            webhookMessageCreateAction.addContent(" by ")
            webhookMessageCreateAction.addContent("Author")
            webhookMessageCreateAction.addContent("` [`")
            webhookMessageCreateAction.addContent("00:00:01")
            webhookMessageCreateAction.addContent("`]\n")
        }
    }

    @Test
    fun testQueue_WithMultipleTracksInTheQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(2)
        val track2 = mockk<AudioTrack>()
        every { track2.info } returns AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri")
        every { track2.duration } returns 1000L
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        every { MusicCommandTest.trackScheduler.queue } returns queue

        // Act
        queueCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.event.hook.sendMessage("**Current Queue:**\n") }
        verifyOrder {
            webhookMessageCreateAction.addContent("#")
            webhookMessageCreateAction.addContent("1")
            webhookMessageCreateAction.addContent(" `")
            webhookMessageCreateAction.addContent("Title")
            webhookMessageCreateAction.addContent(" by ")
            webhookMessageCreateAction.addContent("Author")
            webhookMessageCreateAction.addContent("` [`")
            webhookMessageCreateAction.addContent("00:00:01")
            webhookMessageCreateAction.addContent("`]\n")
            webhookMessageCreateAction.addContent("2")
            webhookMessageCreateAction.addContent("Another Title")
            webhookMessageCreateAction.addContent("Another Author")
        }
    }
}
