package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.webhookMessageCreateAction
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
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andPausableTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)

        //Act
        pauseCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event.hook, Mockito.times(1))
            .sendMessage("Pausing: `")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction as WebhookMessageCreateAction<Message>?,
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
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andNonPausableTrack() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        Mockito.`when`(CommandTest.requestingUserDto.superUser).thenReturn(false)

        //Act
        pauseCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!"),
            ArgumentMatchers.eq("00:00:01"),
            ArgumentMatchers.eq("00:00:01")
        )
    }

    @Test
    fun test_pauseMethod_withCorrectChannels_andNonPausableTrack_AndAQueue() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(1)
        queue.add(MusicCommandTest.track)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(queue)
        Mockito.`when`(CommandTest.requestingUserDto.superUser).thenReturn(false)

        //Act
        pauseCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Our daddy taught us not to be ashamed of our playlists"))
    }
}