import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.*
import io.mockk.impl.annotations.MockK
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.components.ItemComponent
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.helpers.MusicPlayerHelper
import toby.helpers.MusicPlayerHelper.NowPlayingInfo
import toby.lavaplayer.PlayerManager

class MusicPlayerHelperTest {

    @MockK
    private lateinit var playerManager: PlayerManager

    @MockK
    private lateinit var guildMock: Guild

    @MockK
    private lateinit var channelMock: Channel

    @MockK
    private lateinit var audioPlayer: AudioPlayer

    @MockK
    private lateinit var track: AudioTrack

    @MockK
    private lateinit var replyCallback: IReplyCallback

    private val guildId = 123456789L

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)

        // Mock behavior for PlayerManager and AudioPlayer
        every { playerManager.getMusicManager(guildMock) } returns mockk()
        every { playerManager.getMusicManager(guildMock).audioPlayer } returns audioPlayer
        every { audioPlayer.playingTrack } returns track
        every { track.info } returns AudioTrackInfo("Title", "Author", 20L, "Identifier", true, "uri")

        // Mock guild and interaction hook
        every { replyCallback.guild } returns guildMock
        every { guildMock.idLong } returns guildId
        every { replyCallback.hook.interaction } returns mockk {
            every { guild } returns guildMock
            every { channel } returns channelMock
        }
    }

    @AfterEach
    fun tearDown(){
    }

    @Test
    fun `test nowPlaying when no track is playing and no stored message`() {
        // Mock behavior for AudioPlayer when no track is playing
        every { audioPlayer.playingTrack } returns null

        // Mock InteractionHook methods
        val webhookCreateAction = mockk<WebhookMessageCreateAction<Message>>()
        every { replyCallback.hook.sendMessageEmbeds(any<MessageEmbed>()) } returns webhookCreateAction
        every { webhookCreateAction.setEphemeral(true) } returns webhookCreateAction
        every { webhookCreateAction.queue(any()) } just Runs
        every {
            webhookCreateAction.setActionRow(
                any<ItemComponent>(),
                any<ItemComponent>()
            )
        } returns webhookCreateAction


        // Perform nowPlaying action
        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, null)

        // Verify interaction hook behavior
        verify {
            replyCallback.hook.sendMessageEmbeds(match<MessageEmbed> {
                it.description == "There is no track playing currently"
            })
            webhookCreateAction.setEphemeral(true)
            webhookCreateAction.queue(any())
        }
    }

    @Test
    fun `test nowPlaying with no stored nowplaying message sends new message`() {
        // Mock behavior for AudioTrack and InteractionHook
        every { audioPlayer.volume } returns 50
        every { audioPlayer.isPaused } returns false

        // Mock InteractionHook methods for sending a new message
        val webhookCreateAction = mockk<WebhookMessageCreateAction<Message>>()
        every { replyCallback.hook.sendMessageEmbeds(any<MessageEmbed>()) } returns webhookCreateAction
        every { webhookCreateAction.setEphemeral(true) } returns webhookCreateAction
        every {
            webhookCreateAction.setActionRow(
                any<ItemComponent>(),
                any<ItemComponent>()
            )
        } returns webhookCreateAction
        every { webhookCreateAction.queue(any()) } just Runs

        // Perform nowPlaying action
        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, null)

        // Verify interaction hook behavior for sending a new message
        verify {
            replyCallback.hook.sendMessageEmbeds(match<MessageEmbed> {
                it.description == "**Title**: `Title`\n" +
                        "**Author**: `Author`\n" +
                        "**Stream**: `Live`\n"
            })
            webhookCreateAction.queue(any())
        }
    }



    @Test
    fun `test nowPlaying with stored nowplaying message edits existing message`() {
        // Mock behavior for AudioTrack and InteractionHook
        every { audioPlayer.volume } returns 50
        every { audioPlayer.isPaused } returns false

        // Mock InteractionHook methods
        val webhookCreateAction = mockk<WebhookMessageCreateAction<Message>>()
        val webhookMessageEditAction = mockk<WebhookMessageEditAction<Message>>()
        every { replyCallback.hook.sendMessageEmbeds(any<MessageEmbed>()) } returns webhookCreateAction
        every { replyCallback.hook.editOriginalEmbeds(any<MessageEmbed>()) } returns webhookMessageEditAction
        every { replyCallback.hook.deleteOriginal().queue() } just Runs
        every {
            webhookCreateAction.setActionRow(
                any<ItemComponent>(),
                any<ItemComponent>()
            )
        } returns webhookCreateAction
        every {
            webhookMessageEditAction.setActionRow(
                any<ItemComponent>(),
                any<ItemComponent>()
            )
        } returns webhookMessageEditAction
        every { webhookCreateAction.queue(any()) } answers {
            // Simulate storing the message in the map
            MusicPlayerHelper.guildLastNowPlayingMessage[guildId] = NowPlayingInfo(playerManager, replyCallback.hook)
        }
        every { webhookMessageEditAction.queue() } just Runs

        // Clear any existing messages
        MusicPlayerHelper.guildLastNowPlayingMessage.clear()

        // Perform nowPlaying action
        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, null)

        // Verify that a new message was sent and stored
        assert(MusicPlayerHelper.guildLastNowPlayingMessage.containsKey(guildId)) {
            "Expected guildLastNowPlayingMessage to contain a message for guildId $guildId"
        }

        // Perform nowPlaying action again to edit the existing message
        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, null)

        // Verify interaction hook behavior
        verify {
            replyCallback.hook.editOriginalEmbeds(match<MessageEmbed> {
                it.description?.contains("Title") == true && it.description?.contains("Author") == true
            })
            webhookMessageEditAction.queue()
        }

        MusicPlayerHelper.resetMessages(guildId)
    }
}
