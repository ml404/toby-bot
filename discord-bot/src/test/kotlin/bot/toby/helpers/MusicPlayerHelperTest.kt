import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import bot.toby.lavaplayer.TrackScheduler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import database.dto.MusicDto
import database.dto.UserDto
import io.mockk.*
import io.mockk.impl.annotations.MockK
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue

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
        val musicManager = mockk<GuildMusicManager>()
        val trackScheduler = mockk<TrackScheduler>(relaxed = true)
        every { trackScheduler.queue } returns LinkedBlockingQueue()
        every { trackScheduler.isLooping } returns false
        every { trackScheduler.getRequesterId(any()) } returns null
        every { musicManager.audioPlayer } returns audioPlayer
        every { musicManager.scheduler } returns trackScheduler
        every { playerManager.getMusicManager(guildMock) } returns musicManager
        every { audioPlayer.playingTrack } returns track
        every { track.info } returns AudioTrackInfo("Title", "Author", 20L, "Identifier", true, "uri")
        every { track.sourceManager } returns null
        every { track.duration } returns 20L
        every { track.position } returns 0L

        // Mock guild and interaction hook
        every { replyCallback.guild } returns guildMock
        every { guildMock.idLong } returns guildId
        every { guildMock.id } returns guildId.toString()
        every { guildMock.name } returns "guildName"
        every { guildMock.getMemberById(any<Long>()) } returns null
        every { replyCallback.hook.interaction } returns mockk {
            every { guild } returns guildMock
            every { channel } returns channelMock
        }
        every { replyCallback.member } returns mockk(relaxed = true)
        every { replyCallback.hook.deleteOriginal().queue() } just Runs
    }

    @AfterEach
    fun tearDown() {
        MusicPlayerHelper.nowPlayingManager.clear()
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
            webhookCreateAction.setComponents(any<ActionRow>())
        } returns webhookCreateAction


        // Perform nowPlaying action
        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)

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
        every { audioPlayer.volume } returns 50
        every { audioPlayer.isPaused } returns false

        val webhookCreateAction = mockk<WebhookMessageCreateAction<Message>>()
        val message = mockk<Message>(relaxed = true)
        createWebhookMocking(webhookCreateAction, message)

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)

        verify {
            replyCallback.hook.sendMessageEmbeds(match<MessageEmbed> {
                // Live-stream embed shows the LIVE indicator in the description and
                // surfaces the author byline.
                it.title == "Title" &&
                    it.description?.contains("By `Author`") == true &&
                    it.description?.contains("LIVE") == true
            })
            webhookCreateAction.queue(any())
        }
    }

    @Test
    fun `test nowPlaying embed includes volume and paused fields`() {
        every { audioPlayer.volume } returns 50
        every { audioPlayer.isPaused } returns true

        val webhookCreateAction = mockk<WebhookMessageCreateAction<Message>>()
        val message = mockk<Message>(relaxed = true)
        createWebhookMocking(webhookCreateAction, message)

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)

        verify {
            replyCallback.hook.sendMessageEmbeds(match<MessageEmbed> {
                val volumeField = it.fields.firstOrNull { f -> f.name == "Volume" }
                val pausedField = it.fields.firstOrNull { f -> f.name == "Paused" }
                volumeField?.value?.contains("50") == true &&
                    pausedField?.value?.contains("Yes") == true
            })
        }
    }

    @Test
    fun `test nowPlaying with stored nowplaying message edits existing message`() {
        every { audioPlayer.volume } returns 50
        every { audioPlayer.isPaused } returns false

        val messageEditAction = mockk<MessageEditAction>(relaxed = true)
        val message = mockk<Message>(relaxed = true)
        val webhookCreateAction = mockk<WebhookMessageCreateAction<Message>>()
        createWebhookMocking(webhookCreateAction, message)
        editWebhookMocking(messageEditAction, message)
        MusicPlayerHelper.nowPlayingManager.clear()

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)

        assertNotNull(MusicPlayerHelper.nowPlayingManager.getLastNowPlayingMessage(guildId)) {
            "Expected guildLastNowPlayingMessage to contain a message for guildId $guildId"
        }

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)

        verify {
            message.editMessageEmbeds(match<MessageEmbed> {
                it.title == "Title" && it.description?.contains("Author") == true
            })
            messageEditAction.setComponents(any<ActionRow>()).queue()
        }
    }

    @Test
    fun `playUserIntro routes through loadAndPlayIntro (not loadAndPlay) when user has musicDto`() {
        mockkObject(PlayerManager.Companion)
        try {
            every { PlayerManager.instance } returns playerManager
            every { audioPlayer.volume } returns 50

            val musicDto = MusicDto().apply {
                fileName = "https://example.com/intro.mp3"
                introVolume = 88
                startMs = 0
                endMs = 2500
            }
            val userDto = mockk<UserDto>(relaxed = true) {
                every { musicDtos } returns mutableListOf(musicDto)
            }

            every {
                playerManager.loadAndPlayIntro(
                    guildMock, null, "https://example.com/intro.mp3", 5,
                    0L, 88, 2500L,
                )
            } just Runs
            every { playerManager.setPreviousVolume(50) } just Runs

            MusicPlayerHelper.playUserIntro(userDto, guildMock, null, 5)

            verify(exactly = 1) {
                playerManager.loadAndPlayIntro(
                    guildMock, null, "https://example.com/intro.mp3", 5,
                    0L, 88, 2500L,
                )
            }
            // Crucially: the old code path must NOT be used for intros.
            verify(exactly = 0) {
                playerManager.loadAndPlay(any(), any(), any(), any(), any(), any(), any(), any())
            }
            verify(exactly = 1) { playerManager.setPreviousVolume(50) }
        } finally {
            unmockkObject(PlayerManager.Companion)
        }
    }

    @Test
    fun `playUserIntro falls back to currentVolume when musicDto introVolume is null`() {
        mockkObject(PlayerManager.Companion)
        try {
            every { PlayerManager.instance } returns playerManager
            every { audioPlayer.volume } returns 42

            val musicDto = MusicDto().apply {
                fileName = "https://example.com/intro.mp3"
                introVolume = null
            }
            val userDto = mockk<UserDto>(relaxed = true) {
                every { musicDtos } returns mutableListOf(musicDto)
            }

            every {
                playerManager.loadAndPlayIntro(
                    guildMock, null, "https://example.com/intro.mp3", 5,
                    0L, 42, null,
                )
            } just Runs
            every { playerManager.setPreviousVolume(42) } just Runs

            MusicPlayerHelper.playUserIntro(userDto, guildMock, null, 5)

            verify(exactly = 1) {
                playerManager.loadAndPlayIntro(
                    guildMock, null, "https://example.com/intro.mp3", 5,
                    0L, 42, null,
                )
            }
        } finally {
            unmockkObject(PlayerManager.Companion)
        }
    }

    @Test
    fun `playUserIntro does nothing when user has no musicDto`() {
        mockkObject(PlayerManager.Companion)
        try {
            every { PlayerManager.instance } returns playerManager
            every { audioPlayer.volume } returns 50

            val userDto = mockk<UserDto>(relaxed = true) {
                every { musicDtos } returns mutableListOf()
            }

            MusicPlayerHelper.playUserIntro(userDto, guildMock, null, 5)

            verify(exactly = 0) {
                playerManager.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any())
            }
            verify(exactly = 0) {
                playerManager.loadAndPlay(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            unmockkObject(PlayerManager.Companion)
        }
    }

    private fun editWebhookMocking(
        messageEditAction: MessageEditAction,
        message: Message
    ) {
        every {
            message.editMessageEmbeds(
                any<MessageEmbed>()
            )
        } returns messageEditAction
        every {
            messageEditAction.setComponents(any<ActionRow>()).queue()
        } just Runs

    }

    private fun createWebhookMocking(
        webhookCreateAction: WebhookMessageCreateAction<Message>,
        message: Message
    ) {
        every { replyCallback.hook.sendMessageEmbeds(any<MessageEmbed>()) } returns webhookCreateAction
        every {
            webhookCreateAction.setComponents(any<ActionRow>())
        } returns webhookCreateAction
        every { webhookCreateAction.queue(any()) } answers {
            MusicPlayerHelper.nowPlayingManager.setNowPlayingMessage(guildId, message)
        }
    }
}
