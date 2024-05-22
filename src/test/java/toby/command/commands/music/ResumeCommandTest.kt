package toby.command.commands.music

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.webhookMessageCreateAction

internal class ResumeCommandTest : MusicCommandTest {
    private var resumeCommand: ResumeCommand? = null

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        resumeCommand = ResumeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun test_resumeMethod_withCorrectChannels_andPausableTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`<WebhookMessageCreateAction<Message>>(CommandTest.interactionHook.sendMessage("Resuming: "))
            .thenReturn(webhookMessageCreateAction as WebhookMessageCreateAction<Message>?)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(true)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)

        //Act
        resumeCommand!!.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event.hook, Mockito.times(1))
            .sendMessage("Resuming: `")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Title")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("` by `")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Author")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("`")
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(1)).isPaused
        Mockito.verify(MusicCommandTest.audioPlayer, Mockito.times(1)).isPaused = false
    }
}