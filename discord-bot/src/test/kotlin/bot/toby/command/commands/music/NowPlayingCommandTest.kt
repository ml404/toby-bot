import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.music.MusicCommandTest
import bot.toby.command.commands.music.MusicCommandTest.Companion.mockAudioPlayer
import bot.toby.command.commands.music.MusicCommandTest.Companion.track
import bot.toby.command.commands.music.player.NowPlayingCommand
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        val commandContext = DefaultCommandContext(event)
        every { mockAudioPlayer.playingTrack } returns null

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
        val commandContext = DefaultCommandContext(event)
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
        val commandContext = DefaultCommandContext(event)
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
        assert(messageEmbed.description == "**Title**: `Title`\n" +
                "**Author**: `Author`\n" +
                "**Stream**: `Live`\n")
        assert(messageEmbed.fields[0].name == "Volume")
        assert(messageEmbed.fields[0].value == "0")
        assert(messageEmbed.url == null)
    }

    @Test
    fun testNowPlaying_withCurrentTrackNotStream_printsTrackWithTimestamps() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = DefaultCommandContext(event)
        val audioTrackInfo = AudioTrackInfo("Title", "Author", 1000L, "Identifier", false, "uri")
        every { mockAudioPlayer.playingTrack } returns track
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
        assert(messageEmbed.description == "**Title**: `Title`\n" +
                "**Author**: `Author`\n" +
                "**Progress**: `00:00:01 / 00:00:03`\n")
        assert(messageEmbed.fields[0].name == "Volume")
        assert(messageEmbed.fields[0].value == "0")
        assert(messageEmbed.url == null)
    }
}
