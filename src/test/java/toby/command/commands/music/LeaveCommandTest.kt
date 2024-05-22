package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.commands.music.MusicCommandTest.Companion.musicManager
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import toby.jpa.service.IConfigService
import java.util.concurrent.LinkedBlockingQueue

internal class LeaveCommandTest : MusicCommandTest {
    lateinit var command: LeaveCommand

    @Mock
    lateinit var configService: IConfigService

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        configService = Mockito.mock(IConfigService::class.java)
        command = LeaveCommand(configService)
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun test_leaveCommand() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(MusicCommandTest.audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(MusicCommandTest.playerManager.isCurrentlyStoppable).thenReturn(false)
        Mockito.`when`<AudioChannelUnion>(MusicCommandTest.memberVoiceState.channel)
            .thenReturn(MusicCommandTest.audioChannelUnion)
        Mockito.`when`(MusicCommandTest.audioChannelUnion.name).thenReturn("Channel Name")
        val queue: LinkedBlockingQueue<AudioTrack?> = Mockito.mock(
            LinkedBlockingQueue::class.java
        ) as LinkedBlockingQueue<AudioTrack?>
        Mockito.`when`(trackScheduler.queue).thenReturn(queue)

        //Act
        command.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(CommandTest.event.hook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Disconnecting from `\uD83D\uDD0A %s`"),
            ArgumentMatchers.eq("Channel Name")
        )
        Mockito.verify(musicManager.scheduler, Mockito.times(1)).isLooping =
            false
        Mockito.verify(
            musicManager.scheduler.queue,
            Mockito.times(1)
        ).clear()
        Mockito.verify(musicManager.audioPlayer, Mockito.times(1)).stopTrack()
        Mockito.verify(musicManager.audioPlayer, Mockito.times(1)).volume = 100
        Mockito.verify(MusicCommandTest.audioManager, Mockito.times(1)).closeAudioConnection()
    }
}