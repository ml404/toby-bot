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
import toby.command.commands.music.MusicCommandTest.Companion.musicManager
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.track
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import java.util.concurrent.ArrayBlockingQueue

internal class NowDigOnThisCommandTest : MusicCommandTest {
    lateinit var nowDigOnThisCommand: NowDigOnThisCommand
    
    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        nowDigOnThisCommand = NowDigOnThisCommand()
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun test_nowDigOnThisCommand_withValidArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(playerManager.isCurrentlyStoppable).thenReturn(false)
        val linkOptionalMapping = Mockito.mock(OptionMapping::class.java)
        val volumeOptionalMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("link")).thenReturn(linkOptionalMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionalMapping)
        Mockito.`when`(linkOptionalMapping.asString).thenReturn("www.testlink.com")
        Mockito.`when`(volumeOptionalMapping.asInt).thenReturn(20)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(2)
        val track2 = Mockito.mock(
            AudioTrack::class.java
        )
        Mockito.`when`(track2.info)
            .thenReturn(AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"))
        Mockito.`when`(track2.duration).thenReturn(1000L)
        Mockito.`when`(playerManager.getMusicManager(CommandTest.guild))
            .thenReturn(musicManager)
        queue.add(track)
        queue.add(track2)
        Mockito.`when`(trackScheduler.getQueue()).thenReturn(queue)
        Mockito.`when`(track.userData).thenReturn(1)

        //Act
        nowDigOnThisCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(playerManager, Mockito.times(1)).loadAndPlay(
            ArgumentMatchers.eq(CommandTest.guild),
            ArgumentMatchers.eq(CommandTest.event),
            ArgumentMatchers.eq("www.testlink.com"),
            ArgumentMatchers.eq(false),
            ArgumentMatchers.eq(0),
            ArgumentMatchers.eq(0L),
            ArgumentMatchers.eq(20)
        )
    }

    @Test
    fun test_nowDigOnThisCommand_withInvalidPermissionsArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(playerManager.isCurrentlyStoppable).thenReturn(false)
        val linkOptionalMapping = Mockito.mock(OptionMapping::class.java)
        val volumeOptionalMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("link")).thenReturn(linkOptionalMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("volume")).thenReturn(volumeOptionalMapping)
        Mockito.`when`(linkOptionalMapping.asString).thenReturn("www.testlink.com")
        Mockito.`when`(volumeOptionalMapping.asInt).thenReturn(20)
        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue<AudioTrack>(2)
        val track2 = Mockito.mock(
            AudioTrack::class.java
        )
        Mockito.`when`(track2.info)
            .thenReturn(AudioTrackInfo("Another Title", "Another Author", 1000L, "identifier", true, "uri"))
        Mockito.`when`(track2.duration).thenReturn(1000L)
        Mockito.`when`(playerManager.getMusicManager(CommandTest.guild))
            .thenReturn(musicManager)
        queue.add(track)
        queue.add(track2)
        Mockito.`when`(trackScheduler.getQueue()).thenReturn(queue)
        Mockito.`when`(track.userData).thenReturn(1)
        Mockito.`when`(CommandTest.requestingUserDto.digPermission).thenReturn(false)

        //Act
        nowDigOnThisCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage("I'm gonna put some dirt in your eye Effective Name")
        Mockito.verify(playerManager, Mockito.times(0)).loadAndPlay(
            ArgumentMatchers.eq(CommandTest.guild),
            ArgumentMatchers.eq(CommandTest.event),
            ArgumentMatchers.eq("www.testlink.com"),
            ArgumentMatchers.eq(false),
            ArgumentMatchers.eq(0),
            ArgumentMatchers.eq(0L),
            ArgumentMatchers.eq(20)
        )
    }
}