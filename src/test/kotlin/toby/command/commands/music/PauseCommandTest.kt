package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import io.mockk.clearAllMocks
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import java.util.concurrent.ArrayBlockingQueue

internal class PauseCommandTest : MusicCommandTest {
    lateinit var pauseCommand: PauseCommand

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        pauseCommand = PauseCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andPausableTrack() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true

        // Act
        pauseCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { CommandTest.event.hook.sendMessage("Pausing: `") }
        verify(exactly = 1) { CommandTest.webhookMessageCreateAction.addContent("Title") }
        verify(exactly = 1) { CommandTest.webhookMessageCreateAction.addContent("` by `") }
        verify(exactly = 1) { CommandTest.webhookMessageCreateAction.addContent("Author") }
        verify(exactly = 1) { CommandTest.webhookMessageCreateAction.addContent("`") }
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andNonPausableTrack() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        every { CommandTest.requestingUserDto.superUser } returns false

        // Act
        pauseCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!",
                "00:00:01",
                "00:00:01"
            )
        }
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andNonPausableTrack_AndAQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        every { MusicCommandTest.audioPlayer.isPaused } returns false
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns false
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(1)
        queue.add(MusicCommandTest.track)
        every { MusicCommandTest.trackScheduler.queue } returns queue
        every { CommandTest.requestingUserDto.superUser } returns false

        // Act
        pauseCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessage(
                "Our daddy taught us not to be ashamed of our playlists"
            )
        }
    }
}
