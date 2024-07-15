package toby.command.commands.music

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler

internal class LoopCommandTest : MusicCommandTest {
    lateinit var loopCommand: LoopCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        loopCommand = LoopCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        unmockkAll()
    }

    @Test
    fun test_looping_whenNotCurrentlyLooping() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { audioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns true
        every { trackScheduler.isLooping } returns false

        // Act
        loopCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage("The Player has been set to **looping**")
        }
        verify(exactly = 1) { trackScheduler.isLooping = true }
    }

    @Test
    fun test_looping_whenCurrentlyLooping() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { audioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns true
        every { trackScheduler.isLooping } returns true

        // Act
        loopCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage("The Player has been set to **not looping**")
        }
        verify(exactly = 1) { trackScheduler.isLooping = false }
    }
}
