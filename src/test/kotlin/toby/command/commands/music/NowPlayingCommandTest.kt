import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.commands.music.MusicCommandTest
import toby.command.commands.music.MusicCommandTest.Companion.audioPlayer
import toby.command.commands.music.MusicCommandTest.Companion.track
import toby.command.commands.music.NowPlayingCommand

internal class NowPlayingCommandTest : MusicCommandTest {
    private lateinit var nowPlayingCommand: NowPlayingCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        every { event.hook.interaction } returns mockk {
            every { guild?.idLong } returns 1L
            every { channel } returns mockk(relaxed = true)
        }
        nowPlayingCommand = NowPlayingCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        unmockkAll()
    }

    @Test
    fun testNowPlaying_withNoCurrentTrack_throwsError() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { audioPlayer.playingTrack } returns null

        // Capture slot for MessageEmbed
        val embedSlot = slot<MessageEmbed>()

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }

        // Verify properties of captured MessageEmbed
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "No Track Playing")
        assert(messageEmbed.description == "There is no track playing currently")
    }

    @Test
    fun testNowPlaying_withoutCorrectPermission_throwsError() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { CommandTest.requestingUserDto.musicPermission } returns false

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                "You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name"
            )
        }
    }

    @Test
    fun testNowPlaying_withCurrentTrackStream_printsTrack() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        every { track.userData } returns 1

        // Capture slot for MessageEmbed
        val embedSlot = slot<MessageEmbed>()

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }

        // Verify properties of captured MessageEmbed
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "Now Playing")
        assert(messageEmbed.description == "`Title` by `Author`")
        assert(messageEmbed.fields[0].name == "Volume")
        assert(messageEmbed.fields[0].value == "0")
        assert(messageEmbed.url == null)
    }

    @Test
    fun testNowPlaying_withCurrentTrackNotStream_printsTrackWithTimestamps() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val audioTrackInfo = AudioTrackInfo("Title", "Author", 1000L, "Identifier", false, "uri")
        every { audioPlayer.playingTrack } returns track
        every { track.info } returns audioTrackInfo
        every { track.userData } returns 1
        every { track.position } returns 1000L
        every { track.duration } returns 3000L

        // Capture slot for MessageEmbed
        val embedSlot = slot<MessageEmbed>()

        // Act
        nowPlayingCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            CommandTest.requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(capture(embedSlot))
        }

        // Verify properties of captured MessageEmbed
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "Now Playing")
        assert(messageEmbed.description == "`Title` by `Author` `[00:00:01/00:00:03]`")
        assert(messageEmbed.fields[0].name == "Volume")
        assert(messageEmbed.fields[0].value == "0")
        assert(messageEmbed.url == null)
    }
}
