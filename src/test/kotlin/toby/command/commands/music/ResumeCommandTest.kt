package toby.command.commands.music

import io.mockk.*
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import java.awt.Color

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
        val audioPlayer = MusicCommandTest.mockAudioPlayer
        val playerManager = MusicCommandTest.playerManager
        val hook = event.hook
        every { audioPlayer.isPaused } returns true
        every { playerManager.isCurrentlyStoppable } returns true
        every { event.getOption("resume") } returns mockk {
            every { asInt } returns 1
        }



        // Act
        resumeCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        val embedSlot = slot<MessageEmbed>()
        // Assert
        verify(exactly = 1) { hook.sendMessageEmbeds(capture(embedSlot)) }
        verify(exactly = 1) { audioPlayer.isPaused = false }

        // Assert on the captured EmbedBuilder
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "Track Pause/Resume")
        assert(messageEmbed.description == "Resuming: `Title` by `Author`")
        assert(messageEmbed.color == Color.CYAN)
    }
}
