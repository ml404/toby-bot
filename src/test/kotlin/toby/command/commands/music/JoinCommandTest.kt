package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.track
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import toby.jpa.service.IConfigService
import java.util.concurrent.ArrayBlockingQueue

internal class JoinCommandTest : MusicCommandTest {
    lateinit var command: JoinCommand

    @Mock
    lateinit var configService: IConfigService

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        configService = Mockito.mock(IConfigService::class.java)
        command = JoinCommand(configService)
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun test_joinCommand() {
        setUpAudioChannelsWithBotNotInChannel()
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(audioPlayer.isPaused).thenReturn(false)
        Mockito.`when`(audioPlayer.playingTrack).thenReturn(track)
        Mockito.`when`(playerManager.isCurrentlyStoppable).thenReturn(false)
        Mockito.`when`<AudioChannelUnion>(MusicCommandTest.memberVoiceState.channel)
            .thenReturn(MusicCommandTest.audioChannelUnion)
        Mockito.`when`(MusicCommandTest.audioChannelUnion.name).thenReturn("Channel Name")
        val queue: ArrayBlockingQueue<AudioTrack?> = Mockito.mock(
            ArrayBlockingQueue::class.java
        ) as ArrayBlockingQueue<AudioTrack?>
        Mockito.`when`(trackScheduler.getQueue()).thenReturn(queue)
        Mockito.`when`<GuildVoiceState>(CommandTest.member.voiceState)
            .thenReturn(MusicCommandTest.memberVoiceState)
        Mockito.`when`(MusicCommandTest.memberVoiceState.inAudioChannel()).thenReturn(true)
        Mockito.`when`(MusicCommandTest.botMember.hasPermission(Permission.VOICE_CONNECT))
            .thenReturn(true)

        //Act
        command.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        //Assert
        Mockito.verify(MusicCommandTest.audioManager, Mockito.times(1))
            .openAudioConnection(MusicCommandTest.audioChannelUnion)
        Mockito.verify(playerManager, Mockito.times(1))
            .getMusicManager(CommandTest.guild)
        Mockito.verify(MusicCommandTest.musicManager.audioPlayer, Mockito.times(1)).volume = 100
        Mockito.verify(CommandTest.event.hook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Connecting to `\uD83D\uDD0A %s` with volume '%s'"),
            ArgumentMatchers.eq("Channel Name"),
            ArgumentMatchers.eq(100)
        )
    }
}