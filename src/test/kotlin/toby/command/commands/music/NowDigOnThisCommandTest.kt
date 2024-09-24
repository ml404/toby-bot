package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.guild
import toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.musicManager
import toby.command.commands.music.MusicCommandTest.Companion.playerManager
import toby.command.commands.music.MusicCommandTest.Companion.track
import toby.command.commands.music.MusicCommandTest.Companion.trackScheduler
import toby.command.commands.music.player.NowDigOnThisCommand
import java.util.concurrent.ArrayBlockingQueue

internal class NowDigOnThisCommandTest : MusicCommandTest {
    private lateinit var nowDigOnThisCommand: NowDigOnThisCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        nowDigOnThisCommand = NowDigOnThisCommand()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun test_nowDigOnThisCommand_withValidArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false
        every { playerManager.getMusicManager(guild) } returns musicManager

        val linkOptionalMapping = mockk<OptionMapping>()
        val volumeOptionalMapping = mockk<OptionMapping>()
        every { event.getOption("link") } returns linkOptionalMapping
        every { event.getOption("volume") } returns volumeOptionalMapping
        every { event.getOption("start") } returns mockk(relaxed = true)
        every { linkOptionalMapping.asString } returns "www.testlink.com"
        every { volumeOptionalMapping.asInt } returns 20
        every { playerManager.loadAndPlay(any(), any(), any(), any(), any(), any(), any()) } just Runs

        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(2)
        val track2 = mockk<AudioTrack>()
        every { track2.info } returns AudioTrackInfo(
            "Another Title",
            "Another Author",
            1000L,
            "identifier",
            true,
            "uri"
        )
        every { track2.duration } returns 1000L
        queue.add(track)
        queue.add(track2)
        every { trackScheduler.queue } returns queue
        every { track.userData } returns 1

        // Act
        nowDigOnThisCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        verify(exactly = 1) {
            playerManager.loadAndPlay(
                guild,
                event,
                "www.testlink.com",
                false,
                0,
                0L,
                20
            )
        }
    }

    @Test
    fun test_nowDigOnThisCommand_withInvalidPermissionsArguments() {
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { mockAudioPlayer.isPaused } returns false
        every { playerManager.isCurrentlyStoppable } returns false

        val linkOptionalMapping = mockk<OptionMapping>()
        val volumeOptionalMapping = mockk<OptionMapping>()
        every { event.getOption("link") } returns linkOptionalMapping
        every { event.getOption("volume") } returns volumeOptionalMapping
        every { linkOptionalMapping.asString } returns "www.testlink.com"
        every { volumeOptionalMapping.asInt } returns 20

        val queue: ArrayBlockingQueue<AudioTrack> = ArrayBlockingQueue(2)
        val track2 = mockk<AudioTrack>()
        every { track2.info } returns AudioTrackInfo(
            "Another Title",
            "Another Author",
            1000L,
            "identifier",
            true,
            "uri"
        )
        every { track2.duration } returns 1000L
        every { playerManager.getMusicManager(guild) } returns musicManager
        queue.add(track)
        queue.add(track2)
        every { trackScheduler.queue } returns queue
        every { track.userData } returns 1
        every { CommandTest.requestingUserDto.digPermission } returns false

        // Act
        nowDigOnThisCommand.handleMusicCommand(
            commandContext,
            playerManager,
            CommandTest.requestingUserDto,
            0
        )

        verify(exactly = 1) {
            event.hook.sendMessage("I'm gonna put some dirt in your eye Effective Name")
        }
        verify(exactly = 0) {
            playerManager.loadAndPlay(
                guild,
                event,
                "www.testlink.com",
                false,
                0,
                0L,
                20
            )
        }
    }
}