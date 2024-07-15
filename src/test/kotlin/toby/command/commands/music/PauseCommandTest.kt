package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.requestingUserDto
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import java.util.concurrent.ArrayBlockingQueue

private const val songDuration = "00:00:01"

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
        val commandContext = CommandContext(event)
        every { audioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns true

        // Act
        pauseCommand.handleMusicCommand(
            commandContext,
            playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { event.hook.sendMessage("Pausing: `Title` by `Author`") }
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andNonPausableTrack() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { audioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false
        every { requestingUserDto.superUser } returns false

        // Act
        pauseCommand.handleMusicCommand(
            commandContext,
            playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR $songDuration, $songDuration OF PLAYTIME!")
        }
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andNonPausableTrack_AndAQueue() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { audioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(1)
        queue.add(MusicCommandTest.track)
        every { MusicCommandTest.trackScheduler.queue } returns queue
        every { requestingUserDto.superUser } returns false

        // Act
        pauseCommand.handleMusicCommand(
            commandContext,
            playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage(
                "Our daddy taught us not to be ashamed of our playlists"
            )
        }
    }
}
