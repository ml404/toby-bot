package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import java.util.concurrent.ArrayBlockingQueue

internal class QueueCommandTest : MusicCommandTest {
    var queueCommand: QueueCommand? = null

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        queueCommand = QueueCommand()
    }

    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun testQueue_WithNoTrackInTheQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)

        //Act
        queueCommand!!.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event.hook, Mockito.times(1))
            .sendMessage("The queue is currently empty")
    }

    @Test
    fun testQueue_WithOneTrackInTheQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(1)
        queue.add(MusicCommandTest.track)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(queue)

        //Act
        queueCommand!!.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event.hook, Mockito.times(1))
            .sendMessage("**Current Queue:**\n")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction as WebhookMessageCreateAction<Message>?,
            Mockito.times(1)
        ).addContent("#")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("1")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent(" `")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Title")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent(" by ")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Author")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("` [`")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("00:00:01")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("`]\n")
    }

    @Test
    fun testQueue_WithMultipleTracksInTheQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(2)
        val track2 = Mockito.mock(
            AudioTrack::class.java
        )
        Mockito.`when`(track2.info)
            .thenReturn(AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"))
        Mockito.`when`(track2.duration).thenReturn(1000L)
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(queue)

        //Act
        queueCommand!!.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event.hook, Mockito.times(1))
            .sendMessage("**Current Queue:**\n")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction as WebhookMessageCreateAction<Message>?,
            Mockito.times(2)
        ).addContent("#")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("1")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(2)
        ).addContent(" `")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Title")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(2)
        ).addContent(" by ")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Author")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(2)
        ).addContent("` [`")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(2)
        ).addContent("00:00:01")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(2)
        ).addContent("`]\n")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("2")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Another Title")
        Mockito.verify<WebhookMessageCreateAction<Message>>(
            webhookMessageCreateAction,
            Mockito.times(1)
        ).addContent("Another Author")
    }
}