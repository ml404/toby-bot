package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import java.util.concurrent.ArrayBlockingQueue

internal class ShuffleCommandTest : MusicCommandTest {
    var shuffleCommand: ShuffleCommand? = null

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        shuffleCommand = ShuffleCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun testShuffleCommand_withValidQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("skip")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asInt).thenReturn(2)
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
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)

        //Act
        shuffleCommand!!.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("The queue has been shuffled 🦧"))
    }

    @Test
    fun testShuffleCommand_withNoQueue() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("skip")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asInt).thenReturn(2)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(1)
        val track2 = Mockito.mock(
            AudioTrack::class.java
        )
        Mockito.`when`(track2.info)
            .thenReturn(AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"))
        Mockito.`when`(track2.duration).thenReturn(1000L)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(queue)
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)

        //Act
        shuffleCommand!!.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("I can't shuffle a queue that doesn't exist"))
    }
}