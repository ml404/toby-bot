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

internal class SkipCommandTest : MusicCommandTest {
    lateinit var skipCommand: SkipCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        skipCommand = SkipCommand()
        Mockito.`when`(CommandTest.event.deferReply(true))
            .thenReturn(CommandTest.replyCallbackAction)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun test_skipCommand_withValidQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(2)
        val track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true, "uri")
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(queue)
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)

        //Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(1)).isLooping = false
        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(1)).nextTrack()
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.eq("Skipped %d track(s)"), ArgumentMatchers.eq(1))
    }

    @Test
    fun test_skipCommandForMultipleTracks_withValidQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(true)
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("skip")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asInt).thenReturn(2)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(3)
        val track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true, "uri")
        val track3 = mockAudioTrack("Another Title 1", "Another Author 1", "identifier", 1000L, true, "uri")
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        queue.add(track3)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(queue)
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)

        //Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(1)).isLooping = false
        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(2)).nextTrack()
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.eq("Skipped %d track(s)"), ArgumentMatchers.eq(2))
    }

    @Test
    fun test_skipCommandWithInvalidAmountOfTracksToSkip_withValidQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("skip")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asInt).thenReturn(-1)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(2)
        val track2 = mockAudioTrack("Another Title", "Another Author", "identifier", 1000L, true, "uri")
        queue.add(MusicCommandTest.track)
        queue.add(track2)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(queue)
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)

        //Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(0)).isLooping = false
        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(0)).nextTrack()
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("You're not too bright, but thanks for trying"))
    }

    @Test
    fun test_skipCommandWithValidNumberOfTracksToSkip_withNoQueueAndSetup() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("skip")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asInt).thenReturn(1)
        Mockito.`when`(MusicCommandTest.trackScheduler.queue).thenReturn(null)
        Mockito.`when`(MusicCommandTest.track.userData).thenReturn(1)
        Mockito.`when`(MusicCommandTest.audioPlayer.playingTrack).thenReturn(null)

        //Act
        skipCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(0)).isLooping = false
        Mockito.verify(MusicCommandTest.trackScheduler, Mockito.times(0)).nextTrack()
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("There is no track playing currently"))
    }

    companion object {
        private fun mockAudioTrack(
            title: String,
            author: String,
            identifier: String,
            songLength: Long,
            isStream: Boolean,
            uri: String
        ): AudioTrack {
            val track2 = Mockito.mock(
                AudioTrack::class.java
            )
            Mockito.`when`(track2.info).thenReturn(AudioTrackInfo(title, author, songLength, identifier, isStream, uri))
            Mockito.`when`(track2.duration).thenReturn(songLength)
            return track2
        }
    }
}