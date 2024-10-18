package bot.toby.command.commands.music

import bot.toby.command.CommandContextImpl
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import bot.toby.command.commands.music.MusicCommandTest.Companion.playerManager
import bot.toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import bot.toby.command.commands.music.player.LoopCommand
import io.mockk.every
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        val commandContext = CommandContextImpl(event)
        every { mockAudioPlayer.isPaused } returns false
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
        val commandContext = CommandContextImpl(event)
        every { mockAudioPlayer.isPaused } returns false
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
