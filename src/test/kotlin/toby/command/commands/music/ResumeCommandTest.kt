package toby.command.commands.music

import io.mockk.*
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.webhookMessageCreateAction

internal class ResumeCommandTest : MusicCommandTest {
    private lateinit var resumeCommand: ResumeCommand

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        resumeCommand = ResumeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun test_resumeMethod_withCorrectChannels_andPausableTrack() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val hook = event.hook
        every { CommandTest.interactionHook.sendMessage("Resuming: ") } returns webhookMessageCreateAction as WebhookMessageCreateAction<Message>
        every { MusicCommandTest.audioPlayer.isPaused } returns true
        every { MusicCommandTest.playerManager.isCurrentlyStoppable } returns true

        // Act
        resumeCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) { hook.sendMessage("Resuming: `Title` by `Author`") }
        verify { MusicCommandTest.audioPlayer.isPaused = false }
    }
}
