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

internal class PlayCommandTest : MusicCommandTest {
    private lateinit var playCommand: PlayCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        playCommand = PlayCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun test_playcommand_withValidArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        val linkOptionalMapping = Mockito.mock(OptionMapping::class.java)
        val typeOptionalMapping = Mockito.mock(OptionMapping::class.java)
        val volumeOptionalMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("type")).thenReturn(typeOptionalMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("link")).thenReturn(linkOptionalMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionalMapping)
        Mockito.`when`(typeOptionalMapping.asString).thenReturn("link")
        Mockito.`when`(linkOptionalMapping.asString).thenReturn("www.testlink.com")
        Mockito.`when`(volumeOptionalMapping.asInt).thenReturn(20)
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
        playCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(MusicCommandTest.playerManager, Mockito.times(1)).loadAndPlay(
            ArgumentMatchers.eq(CommandTest.guild),
            ArgumentMatchers.eq(CommandTest.event),
            ArgumentMatchers.eq("www.testlink.com"),
            ArgumentMatchers.eq(true),
            ArgumentMatchers.eq(0),
            ArgumentMatchers.eq(0L),
            ArgumentMatchers.eq(20)
        )
    }
}