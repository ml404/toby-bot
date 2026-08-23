import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.intro.IntroFailedEvent
import bot.toby.lavaplayer.SchedulerEvents
import bot.toby.lavaplayer.PlayerManager
import bot.toby.lavaplayer.TrackScheduler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import database.dto.music.MusicDto
import database.dto.user.UserDto
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import common.media.MediaToken
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
            messageEditAction.setComponents(any<ActionRow>()).queue(any(), any())
        }
    }

    @Test
    fun `test nowPlaying falls back to a new message when editing a stale stored message fails`() {
        every { audioPlayer.volume } returns 50
        every { audioPlayer.isPaused } returns false

        // Pre-store a stale message whose edit will fail (deleted on Discord's side).
        val staleMessage = mockk<Message>(relaxed = true)
        val failingEdit = mockk<MessageEditAction>(relaxed = true)
        every { staleMessage.editMessageEmbeds(any<MessageEmbed>()) } returns failingEdit
        every { failingEdit.setComponents(any<ActionRow>()) } returns failingEdit
        every { failingEdit.queue(any(), any()) } answers {
            // JDA's queue(success, failure) takes java.util.function.Consumer, not a Kotlin lambda.
            secondArg<java.util.function.Consumer<Throwable>>().accept(RuntimeException("10008: Unknown Message"))
        }

        val freshMessage = mockk<Message>(relaxed = true)
        val webhookCreateAction = mockk<WebhookMessageCreateAction<Message>>()
        createWebhookMocking(webhookCreateAction, freshMessage)

        MusicPlayerHelper.nowPlayingManager.clear()
        MusicPlayerHelper.nowPlayingManager.setNowPlayingMessage(guildId, staleMessage)

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)

        // The stale edit was attempted, then a fresh message was posted and stored.
        verify {
            staleMessage.editMessageEmbeds(any<MessageEmbed>())
            replyCallback.hook.sendMessageEmbeds(any<MessageEmbed>())
            webhookCreateAction.queue(any())
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
    fun `an intro with no file name is reported rather than swallowed`() {
        // The row this fix is about: no file name, the URL living in the blob.
        // `it.fileName!!` threw an NPE before the blob was ever read, and
        // playIntro's own runCatching swallowed it — the one intro failure
        // that reached none of the health, outage or notification paths.
        mockkObject(PlayerManager.Companion)
        try {
            every { PlayerManager.instance } returns playerManager
            every { audioPlayer.volume } returns 50
            every { playerManager.setPreviousVolume(any()) } just Runs

            val musicDto = MusicDto().apply {
                fileName = null
                musicBlob = "https://example.com/from-blob.mp3".toByteArray()
                introVolume = 70
            }
            val userDto = mockk<UserDto>(relaxed = true) {
                every { musicDtos } returns mutableListOf(musicDto)
            }
            every {
                playerManager.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any(), any())
            } just Runs

            MusicPlayerHelper.playUserIntro(userDto, guildMock, null, 5)

            verify(exactly = 1) {
                playerManager.loadAndPlayIntro(
                    any(), any(), eq("https://example.com/from-blob.mp3"), any(), any(), any(), any(), any(),
                )
            }
        } finally {
            unmockkObject(PlayerManager.Companion)
        }
    }

    @Test
    fun `an uploaded intro plays from the signed media url`() {
        // Neither the file name nor the blob is a URL, so the audio lives in
        // the database and lavaplayer fetches it from the web endpoint. That
        // endpoint has to stay anonymous for lavaplayer to reach it, and intro
        // ids are guessable — the signature is what stops it being an open
        // read of every uploaded MP3 on every server.
        mockkObject(PlayerManager.Companion)
        try {
            every { PlayerManager.instance } returns playerManager
            every { audioPlayer.volume } returns 50
            every { playerManager.setPreviousVolume(any()) } just Runs

            val uploaded = MusicDto().apply {
                id = "1_1_1"
                fileName = "my-tune.mp3"
                musicBlob = byteArrayOf(0x49, 0x44, 0x33)
                introVolume = 60
            }
            val userDto = mockk<UserDto>(relaxed = true) {
                every { musicDtos } returns mutableListOf(uploaded)
            }
            val url = slot<String>()
            every {
                playerManager.loadAndPlayIntro(any(), any(), capture(url), any(), any(), any(), any(), any())
            } just Runs

            MusicPlayerHelper.playUserIntro(userDto, guildMock, null, 5)

            assertTrue(url.captured.contains("/music?id=1_1_1"), url.captured)
            assertTrue(
                url.captured.contains("${MediaToken.EXPIRY_PARAM}=") &&
                    url.captured.contains("${MediaToken.SIGNATURE_PARAM}="),
                "the url must be signed and expiring: ${url.captured}",
            )
        } finally {
            unmockkObject(PlayerManager.Companion)
        }
    }

    @Test
    fun `a throw between picking and loading reaches the health system`() {
        mockkObject(PlayerManager.Companion)
        mockkObject(SchedulerEvents)
        val published = mutableListOf<Any>()
        every { SchedulerEvents.publish(capture(published)) } just Runs
        try {
            every { PlayerManager.instance } returns playerManager
            every { audioPlayer.volume } returns 50
            every { playerManager.setPreviousVolume(any()) } just Runs
            every {
                playerManager.loadAndPlayIntro(any(), any(), any(), any(), any(), any(), any(), any())
            } throws IllegalStateException("player is gone")

            val intro = MusicDto().apply {
                id = "1_1_1"
                fileName = "https://example.com/a.mp3"
                introVolume = 50
            }
            val userDto = mockk<UserDto>(relaxed = true) {
                every { musicDtos } returns mutableListOf(intro)
            }

            val played = MusicPlayerHelper.playUserIntro(userDto, guildMock, null, 5)

            assertNull(played)
            val failure = published.filterIsInstance<IntroFailedEvent>().single()
            assertEquals("1_1_1", failure.introId)
        } finally {
            unmockkObject(SchedulerEvents)
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
            messageEditAction.setComponents(any<ActionRow>()).queue(any(), any())
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
    /**
     * The wiring between posting a now-playing message and recording it.
     *
     * [MusicPlayerHelper] and `NowPlayingManager` were each covered on their
     * own, which left the seam between them untested — and the seam is where
     * the bug lived. These drive the real callback that JDA would invoke when
     * the send comes back, rather than standing in for it.
     */
    private fun captureNowPlayingSends(): MutableList<java.util.function.Consumer<in Message>> {
        val sends = mutableListOf<java.util.function.Consumer<in Message>>()
        val action = mockk<WebhookMessageCreateAction<Message>>()
        every { replyCallback.hook.sendMessageEmbeds(any<MessageEmbed>()) } returns action
        every { action.setComponents(any<ActionRow>()) } returns action
        every { action.queue(capture(sends)) } just Runs
        every { audioPlayer.volume } returns 50
        every { audioPlayer.isPaused } returns false
        MusicPlayerHelper.nowPlayingManager.clear()
        return sends
    }

    @Test
    fun `a now-playing message that lands after the track ended does not stay behind`() {
        // A track that dies the moment it starts ends inside the send's
        // round-trip, so the teardown ran while the message was still in the
        // air. It arrived with nothing left to remove it: a frozen embed, in
        // the channel for good.
        val sends = captureNowPlayingSends()
        val message = mockk<Message>(relaxed = true)

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)
        MusicPlayerHelper.resetMessages(guildId)
        sends.single().accept(message)

        verify { message.delete() }
        assertNull(MusicPlayerHelper.nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `two now-playing posts racing leave one message, not two`() {
        // A retried intro starts before the first send has landed, finds no
        // message to edit, and posts its own.
        val sends = captureNowPlayingSends()
        val first = mockk<Message>(relaxed = true)
        val second = mockk<Message>(relaxed = true)

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)
        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)
        assertEquals(2, sends.size)
        sends[0].accept(first)
        sends[1].accept(second)

        verify { first.delete() }
        verify(exactly = 0) { second.delete() }
        assertEquals(second, MusicPlayerHelper.nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `the ordinary case still stores the message it just posted`() {
        val sends = captureNowPlayingSends()
        val message = mockk<Message>(relaxed = true)

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)
        sends.single().accept(message)

        verify(exactly = 0) { message.delete() }
        assertEquals(message, MusicPlayerHelper.nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

    @Test
    fun `a message in flight for one guild survives another guild's teardown`() {
        val sends = captureNowPlayingSends()
        val message = mockk<Message>(relaxed = true)

        MusicPlayerHelper.nowPlaying(replyCallback, playerManager, 5)
        MusicPlayerHelper.resetMessages(guildId + 1)
        sends.single().accept(message)

        verify(exactly = 0) { message.delete() }
        assertEquals(message, MusicPlayerHelper.nowPlayingManager.getLastNowPlayingMessage(guildId))
    }

}
