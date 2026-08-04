package bot.toby.helpers

import bot.coroutines.MainCoroutineExtension
import bot.toby.helpers.IntroHelper.Companion.MAX_FILE_SIZE_KB
import database.dto.music.MusicDto
import database.service.guild.ConfigService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.extension.ExtendWith
import java.io.InputStream
import java.net.URI
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class IntroHelperTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var musicFileService: database.service.music.MusicFileService
    private lateinit var configService: ConfigService
    private lateinit var httpHelper: HttpHelper
    private lateinit var event: SlashCommandInteractionEvent
    private lateinit var userDto: database.dto.user.UserDto
    private lateinit var musicDto: MusicDto
    private lateinit var attachment: Attachment
    private lateinit var hook: InteractionHook

    @BeforeEach
    fun setup() {
        userDtoHelper = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        httpHelper = mockk(relaxed = true)

        introHelper = IntroHelper(userDtoHelper, musicFileService, configService, httpHelper)

        event = mockk(relaxed = true)
        userDto = mockk(relaxed = true)
        musicDto = mockk(relaxed = true)
        attachment = mockk(relaxed = true)
        hook = mockk(relaxed = true)
        mockkObject(URLHelper)

        every { event.hook } returns hook
        every { event.user.effectiveName } returns "TestUser"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        unmockkObject(URLHelper)
    }

    @Test
    fun `calculateIntroVolume should return the correct volume`() {
        val optionMapping = mockk<OptionMapping> {
            every { asInt } returns 80
        }
        every { event.getOption("volume") } returns optionMapping

        val result = introHelper.calculateIntroVolume(event)

        assert(result == 80)
        verify { event.getOption("volume") }
    }

    @Test
    fun `calculateIntroVolume should use default if no volume is provided`() {
        every { event.getOption("volume") } returns null
        every { configService.getConfigByName(any(), any()) } returns mockk {
            every { value } returns "70"
        }

        val result = introHelper.calculateIntroVolume(event)

        assert(result == 70)
        verify { configService.getConfigByName(any(), any()) }
    }

    @Test
    fun `calculateIntroVolume should fallback to 100 when no options or config present`() {
        every { event.getOption("volume") } returns null
        every { configService.getConfigByName(any(), any()) } returns null

        val result = introHelper.calculateIntroVolume(event)

        assert(result == 100)
    }

    @Test
    fun `handleMedia should handle valid URL`() {
        every { URLHelper.isValidURL(any()) } returns true

        // Spy on introHelper to allow partial mocking
        val spykIntroHelper = spyk(introHelper)

        // Mock the event details
        every { event.user.effectiveName } returns "TestUser"

        // Call handleMedia
        spykIntroHelper.handleMedia(
            event, userDto, 10, InputData.Url("http://valid.url"), 70, musicDto
        )

        // Verify that handleUrl was called
        verify {
            spykIntroHelper.handleUrl(
                event, userDto, "TestUser", 10, URI.create("http://valid.url"), 70, musicDto
            )
        }
    }

    @Test
    fun `handleMedia should replace youtube shorts URL with correct longform type`() {
        every { URLHelper.isValidURL(any()) } returns true

        // Spy on introHelper to allow partial mocking
        val spykIntroHelper = spyk(introHelper)

        // Mock the event details
        every { event.user.effectiveName } returns "TestUser"

        // Call handleMedia
        spykIntroHelper.handleMedia(
            event, userDto, 10, InputData.Url("http://youtube.com"), 70, musicDto
        )

        // Verify that handleUrl was called
        verify {
            spykIntroHelper.handleUrl(
                event, userDto, "TestUser", 10, URI.create("http://youtube.com"), 70, musicDto
            )
        }
    }

    @Test
    fun `handleMedia should handle valid attachment`() {
        // Create a spyk instance of the actual introHelper
        val spykIntroHelper = spyk(introHelper)

        // Mocking attachment properties
        every { attachment.fileExtension } returns "mp3"
        every { attachment.size } returns 200_000

        // Mocking downloadAttachment to return a valid input stream
        every { spykIntroHelper.downloadAttachment(attachment) } returns mockk<InputStream>()


        spykIntroHelper.handleMedia(event, userDto, 10, InputData.Attachment(attachment), 70, musicDto)

        verify {
            spykIntroHelper.handleAttachment(
                event, userDto, "TestUser", 10, attachment, 70, musicDto
            )
        }
    }

    @Test
    fun `handleMedia should send error for invalid URL`() {
        // Mocking the URL validation helper to return false for invalid URL
        every { URLHelper.isValidURL(any()) } returns false

        // Running the handleMedia method with an invalid URL
        introHelper.handleMedia(event, userDto, 10, InputData.Url("invalid"), 70, musicDto)

        // Verifying that the correct error message is sent
        verify(exactly = 1) {
            hook.sendMessage("Please provide a valid URL.").queue(any())
        }
    }


    @Test
    fun `handleAttachment should reject non-mp3 files`() {
        every { attachment.fileExtension } returns "wav"

        introHelper.handleAttachment(
            event, userDto, "TestUser", 10, attachment, 70, musicDto
        )

        verify {
            hook.sendMessage("Please use mp3 files only").queue(any())
        }
    }

    @Test
    fun `handleAttachment should reject large files`() {
        every { attachment.fileExtension } returns "mp3"
        every { attachment.size } returns 600_000

        introHelper.handleAttachment(
            event, userDto, "TestUser", 10, attachment, 70, musicDto
        )

        verify {
            hook.sendMessage("Please keep the file size under ${MAX_FILE_SIZE_KB}kb").queue(any())
        }
    }

    @Test
    fun `handleAttachment should persist valid mp3 file`() {
        // Create a spyk instance of the actual introHelper
        val spykIntroHelper = spyk(introHelper)

        val inputStream = mockk<InputStream>()

        // Mocking attachment properties
        every { attachment.fileExtension } returns "mp3"
        every { attachment.size } returns 200_000

        // Mocking downloadAttachment to return a valid input stream
        every { spykIntroHelper.downloadAttachment(attachment) } returns inputStream

        // Run the handleAttachment method with the spykIntroHelper
        spykIntroHelper.handleAttachment(
            event, userDto, "TestUser", 10, attachment, 70, musicDto
        )

        // Verify that persistMusicFile was called with correct arguments
        verify {
            spykIntroHelper.persistMusicFile(
                event, userDto, "TestUser", 10, attachment.fileName, 70, inputStream, musicDto
            )
        }
    }


    @Test
    fun `persistMusicFile should create a new music file if no selected dto`() {
        val inputStream = mockk<InputStream>()
        val byteArray = ByteArray(10)
        every { FileUtils.readInputStreamToByteArray(inputStream) } returns byteArray

        introHelper.persistMusicFile(
            event, userDto, "TestUser", 10, "filename.mp3", 70, inputStream, null
        )

        verify {
            musicFileService.createNewMusicFile(any())
        }
    }

    @Test
    fun `persistMusicFile should update existing music file`() {
        val inputStream = mockk<InputStream>()
        val byteArray = ByteArray(10)
        every { FileUtils.readInputStreamToByteArray(inputStream) } returns byteArray

        introHelper.persistMusicFile(
            event, userDto, "TestUser", 10, "filename.mp3", 70, inputStream, musicDto
        )

        verify {
            musicFileService.updateMusicFile(musicDto)
        }
    }

    // --- explaining a save that didn't happen -------------------------------
    //
    // createNewMusicFile returns null for two different reasons, and both used
    // to be reported as a duplicate. Since the persistence layer started
    // refusing an already-occupied slot rather than merging over it, someone
    // whose audio was nowhere in their list could be told it "already exists
    // as one of their intros".

    private fun captureRejection(): MutableList<String> {
        val messages = mutableListOf<String>()
        every { hook.sendMessage(capture(messages)) } returns mockk(relaxed = true)
        return messages
    }

    @Test
    fun `audio the user already uploaded is reported as a duplicate, with its slot`() {
        val inputStream = mockk<InputStream>()
        every { FileUtils.readInputStreamToByteArray(inputStream) } returns ByteArray(10)
        every { musicFileService.createNewMusicFile(any()) } returns null
        every { musicFileService.isFileAlreadyUploaded(any()) } returns
            MusicDto(database.dto.user.UserDto(1234L, 1L), 2, "already-here", 90, ByteArray(10))
        val messages = captureRejection()

        introHelper.persistMusicFile(event, userDto, "TestUser", 10, "filename.mp3", 70, inputStream, null)

        val message = messages.single()
        assertTrue(message.contains("already exists"), message)
        assertTrue(message.contains("slot #2"), message)
    }

    @Test
    fun `a refused slot is not reported as a duplicate that does not exist`() {
        val inputStream = mockk<InputStream>()
        every { FileUtils.readInputStreamToByteArray(inputStream) } returns ByteArray(10)
        every { musicFileService.createNewMusicFile(any()) } returns null
        // Nothing matching was ever uploaded — the save was refused because
        // the target slot was occupied.
        every { musicFileService.isFileAlreadyUploaded(any()) } returns null
        val messages = captureRejection()

        introHelper.persistMusicFile(event, userDto, "TestUser", 10, "filename.mp3", 70, inputStream, null)

        val message = messages.single()
        assertFalse(message.contains("already exists"), "must not claim a duplicate that isn't there: $message")
        assertTrue(message.contains("slot it was going into is already in use"), message)
        assertTrue(message.contains("/setintro"), "tell them how to fix it: $message")
    }

    @Test
    fun `the same distinction applies when the source was a link`() {
        every { musicFileService.createNewMusicFile(any()) } returns null
        every { musicFileService.isFileAlreadyUploaded(any()) } returns null
        val messages = captureRejection()

        introHelper.persistMusicUrl(
            event, userDto, 10, "a title", "https://youtu.be/abc", "TestUser", 70, null,
        )

        assertTrue(messages.single().contains("already in use"), messages.single())
    }

    // --- slot allocation ---------------------------------------------------

    /** A user whose intros sit in [indexes]. */
    private fun userWithSlots(vararg indexes: Int): database.dto.user.UserDto {
        val owner = database.dto.user.UserDto(discordId = 1234L, guildId = 1L)
        owner.musicDtos = indexes.map { MusicDto(owner, it, "intro$it", 90, byteArrayOf(it.toByte())) }
            .toMutableList()
        return owner
    }

    @Test
    fun `a new intro fills the gap a delete left rather than overwriting a slot`() {
        // Regression: slots [1, 3] with the old `size + 1` allocator produced
        // index 3, whose id already existed — and createNewMusicFile turns an
        // id collision into an update, silently replacing the slot-3 intro.
        val owner = userWithSlots(1, 3)
        every { userDtoHelper.calculateUserDto(1234L, 1L) } returns owner

        introHelper.persistMusicUrl(
            event, owner, 10, "new", "http://valid.url", "TestUser", 70, null
        )

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(2, saved.captured.index)
        assertEquals("1_1234_2", saved.captured.id)
    }

    @Test
    fun `a file intro also takes the lowest free slot`() {
        val owner = userWithSlots(2)
        every { userDtoHelper.calculateUserDto(1234L, 1L) } returns owner
        val inputStream = mockk<InputStream>()
        every { FileUtils.readInputStreamToByteArray(inputStream) } returns ByteArray(10)

        introHelper.persistMusicFile(
            event, owner, "TestUser", 10, "filename.mp3", 70, inputStream, null
        )

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(1, saved.captured.index)
    }

    @Test
    fun `slots fill in order when there is no gap`() {
        val owner = userWithSlots(1, 2)
        every { userDtoHelper.calculateUserDto(1234L, 1L) } returns owner

        introHelper.persistMusicUrl(
            event, owner, 10, "new", "http://valid.url", "TestUser", 70, null
        )

        val saved = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(3, saved.captured.index)
    }

    @Test
    fun `a full set saves nothing and says so`() {
        val owner = userWithSlots(1, 2, 3)
        every { userDtoHelper.calculateUserDto(1234L, 1L) } returns owner

        introHelper.persistMusicUrl(
            event, owner, 10, "new", "http://valid.url", "TestUser", 70, null
        )

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { hook.sendMessage(match<String> { it.contains("already have 3 intros") }) }
    }

    @Test
    fun `replacing a chosen intro keeps its slot`() {
        val owner = userWithSlots(1, 2, 3)
        every { userDtoHelper.calculateUserDto(1234L, 1L) } returns owner
        val chosen = owner.musicDtos[1]

        introHelper.persistMusicUrl(
            event, owner, 10, "new", "http://valid.url", "TestUser", 70, chosen
        )

        verify { musicFileService.updateMusicFile(match<MusicDto> { it.index == 2 }) }
    }

    @Test
    fun `persistMusicUrl should persist a valid URL`() {
        introHelper.persistMusicUrl(
            event, userDto, 10, "filename", "http://valid.url", "TestUser", 70, musicDto
        )

        verify {
            musicFileService.updateMusicFile(any())
        }
    }

    @Test
    fun `handleMedia should send error when URL is empty`() {
        // Empty URL input
        introHelper.handleMedia(event, userDto, 10, InputData.Url(""), 70, musicDto)

        // Verify the error message is sent
        verify {
            hook.sendMessage("Please provide a valid URL.").queue(any())
        }
    }

    @Test
    fun `handleAttachment should not persist for invalid attachments`() {
        // Invalid file (wrong extension and too large)
        every { attachment.fileExtension } returns "wav"
        every { attachment.size } returns 500_000

        // Call handleAttachment
        introHelper.handleAttachment(event, userDto, "TestUser", 10, attachment, 70, musicDto)

        // Verify that the musicFileService is never called for invalid attachments
        verify(exactly = 0) { musicFileService.updateMusicFile(any()) }
        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `calculateIntroVolume should clamp volume to max value`() {
        // Mock volume greater than 100
        val optionMapping = mockk<OptionMapping> {
            every { asInt } returns 120
        }
        every { event.getOption("volume") } returns optionMapping

        // Call calculateIntroVolume
        val result = introHelper.calculateIntroVolume(event)

        // Assert that the volume is clamped to 100
        assert(result == 100)
    }

    @Test
    fun `calculateIntroVolume should clamp volume to min value`() {
        // Mock volume less than 0
        val optionMapping = mockk<OptionMapping> {
            every { asInt } returns -10
        }
        every { event.getOption("volume") } returns optionMapping

        // Call calculateIntroVolume
        val result = introHelper.calculateIntroVolume(event)

        // Assert that the volume is clamped to 0
        assert(result == 1)
    }


    @Test
    fun `parseVolume should extract valid volume from message`() {
        val content = "https://www.youtube.com/watch?v=dQw4w9WgXcQ 85"

        val result = introHelper.parseVolume(content)

        assert(result == 85)
    }

    @Test
    fun `parseVolume should return null if no volume is present`() {
        val content = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

        val result = introHelper.parseVolume(content)

        assert(result == null)
    }





    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `checkForOverlyLongIntroDuration returns false when duration is below intro limit`() = runTest {
        // Arrange
        val url = "https://www.youtube.com/watch?v=validVideoId"
        coEvery { httpHelper.getYouTubeVideoDuration(url) } returns 15.seconds

        // Act
        val result = introHelper.checkForOverlyLongIntroDuration(url)
        advanceUntilIdle()

        // Assert
        assertFalse(result)
    }

    @Test
    fun `checkForOverlyLongIntroDuration returns true when duration is above intro limit`() = runTest {
        // Arrange
        val url = "https://www.youtube.com/watch?v=validVideoId"
        coEvery { httpHelper.getYouTubeVideoDuration(url) } returns 25.seconds

        // Act
        val result = introHelper.checkForOverlyLongIntroDuration(url)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `checkForOverlyLongIntroDuration returns false when duration is equal to intro limit`() = runTest {
        // Arrange
        val url = "https://www.youtube.com/watch?v=validVideoId"
        coEvery { httpHelper.getYouTubeVideoDuration(url) } returns 15.seconds

        // Act
        val result = introHelper.checkForOverlyLongIntroDuration(url)

        // Assert
        assertFalse(result) // As the duration equals the limit
    }

    @Test
    fun `checkForOverlyLongIntroDuration returns false when duration is null`() = runTest {
        // Arrange
        val url = "https://www.youtube.com/watch?v=invalidVideoId"
        coEvery { httpHelper.getYouTubeVideoDuration(url) } returns null

        // Act
        val result = introHelper.checkForOverlyLongIntroDuration(url)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `checkForOverlyLongIntroDuration returns true when duration is over a minute`() = runTest {
        // Arrange
        val url = "https://www.youtube.com/watch?v=validVideoId"
        coEvery { httpHelper.getYouTubeVideoDuration(url) } returns 1.minutes

        // Act
        val result = introHelper.checkForOverlyLongIntroDuration(url)

        // Assert
        assertTrue(result) // As the duration equals the limit
    }

    @Test
    fun `checkForOverlyLongIntroDuration returns true when duration is over an hour`() = runTest {
        // Arrange
        val url = "https://www.youtube.com/watch?v=validVideoId"
        coEvery { httpHelper.getYouTubeVideoDuration(url) } returns 1.hours

        // Act
        val result = introHelper.checkForOverlyLongIntroDuration(url)

        // Assert
        assertTrue(result) // As the duration equals the limit
    }

    // handleUrl — title stored as fileName

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleUrl fetches video title and passes it as filename to persistMusicUrl`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testIntroHelper = spyk(IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, testDispatcher))
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

        coEvery { httpHelper.getYouTubeVideoTitle(url) } returns "Never Gonna Give You Up"

        testIntroHelper.handleUrl(event, userDto, "TestUser", 10, URI.create(url), 70, null)
        advanceUntilIdle()

        verify {
            testIntroHelper.persistMusicUrl(
                event, userDto, 10, "Never Gonna Give You Up", url, "TestUser", 70, null
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleUrl falls back to URL as filename when title fetch fails`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testIntroHelper = spyk(IntroHelper(userDtoHelper, musicFileService, configService, httpHelper, testDispatcher))
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

        coEvery { httpHelper.getYouTubeVideoTitle(url) } throws RuntimeException("API error")

        testIntroHelper.handleUrl(event, userDto, "TestUser", 10, URI.create(url), 70, null)
        advanceUntilIdle()

        verify {
            testIntroHelper.persistMusicUrl(
                event, userDto, 10, url, url, "TestUser", 70, null
            )
        }
    }

    // saveUserMusicDto — displayName sets fileName

    // --- pending intros -----------------------------------------------------
    //
    // Half-finished intros used to live in a bare mutableMapOf: written from
    // JDA's dispatch threads and from the modal listener's coroutine, and only
    // ever removed by the replace menu's success path, so every abandoned flow
    // leaked an entry holding a Message.Attachment.

    @Test
    fun `a parked intro comes back to the same user in the same guild`() {
        val pending = PendingIntro(attachment = null, url = "https://youtu.be/a", volume = 60)

        introHelper.parkPendingIntro(7L, 1L, pending)

        assertEquals(pending, introHelper.pendingIntro(7L, 1L))
    }

    @Test
    fun `nothing is waiting for a user who has not started one`() {
        assertNull(introHelper.pendingIntro(7L, 1L))
    }

    @Test
    fun `a form opened in one server cannot be answered in another`() {
        // Keyed on guild as well as user: the payload decides what gets saved,
        // so picking it up in the wrong server would write the wrong intro.
        introHelper.parkPendingIntro(7L, 1L, PendingIntro(null, "https://youtu.be/a", 60))

        assertNull(introHelper.pendingIntro(8L, 1L))
    }

    @Test
    fun `two users in the same guild do not share a pending intro`() {
        introHelper.parkPendingIntro(7L, 1L, PendingIntro(null, "https://youtu.be/mine", 60))
        introHelper.parkPendingIntro(7L, 2L, PendingIntro(null, "https://youtu.be/theirs", 60))

        assertEquals("https://youtu.be/mine", introHelper.pendingIntro(7L, 1L)?.url)
        assertEquals("https://youtu.be/theirs", introHelper.pendingIntro(7L, 2L)?.url)
    }

    @Test
    fun `starting a second intro replaces the first rather than stacking`() {
        introHelper.parkPendingIntro(7L, 1L, PendingIntro(null, "https://youtu.be/first", 60))
        introHelper.parkPendingIntro(7L, 1L, PendingIntro(null, "https://youtu.be/second", 60))

        assertEquals("https://youtu.be/second", introHelper.pendingIntro(7L, 1L)?.url)
    }

    @Test
    fun `clearing releases the payload so an attachment is not held on to`() {
        introHelper.parkPendingIntro(7L, 1L, PendingIntro(mockk(relaxed = true), null, 60))

        introHelper.clearPendingIntro(7L, 1L)

        assertNull(introHelper.pendingIntro(7L, 1L))
    }

    @Test
    fun `clearing one user's pending intro leaves everyone else's alone`() {
        introHelper.parkPendingIntro(7L, 1L, PendingIntro(null, "https://youtu.be/a", 60))
        introHelper.parkPendingIntro(7L, 2L, PendingIntro(null, "https://youtu.be/b", 60))

        introHelper.clearPendingIntro(7L, 1L)

        assertNull(introHelper.pendingIntro(7L, 1L))
        assertNotNull(introHelper.pendingIntro(7L, 2L))
    }

    @Test
    fun `clearing something that was never parked is not an error`() {
        introHelper.clearPendingIntro(7L, 1L)
    }

    @Test
    fun `concurrent parks from different threads neither lose entries nor corrupt the store`() {
        // The old LinkedHashMap could be left in a state where a lookup never
        // returns; JDA dispatches interactions on a pool and the modal
        // listener adds a coroutine on top. Kept comfortably under the cache's
        // 1,000-entry bound so eviction can't be mistaken for a lost write.
        val threads = 8
        val perThread = 100
        val barrier = java.util.concurrent.CyclicBarrier(threads)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            (0 until threads).map { t ->
                pool.submit {
                    barrier.await()
                    repeat(perThread) { i ->
                        val user = (t * perThread + i).toLong()
                        introHelper.parkPendingIntro(7L, user, PendingIntro(null, "https://youtu.be/$user", 50))
                    }
                }
            }.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val readBack = (0 until threads * perThread).map { introHelper.pendingIntro(7L, it.toLong())?.url }
        assertEquals(
            (0 until threads * perThread).map { "https://youtu.be/$it" },
            readBack,
            "every concurrent write should be readable afterwards",
        )
    }

    @Test
    fun `an abandoned intro is released on its own rather than held forever`() {
        // The leak this replaced: only the replace menu's success path ever
        // removed an entry, so closing a form without submitting left a
        // PendingIntro — and its Message.Attachment — alive for the life of
        // the process.
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val helper = IntroHelper(
            userDtoHelper, musicFileService, configService, httpHelper,
            pendingTicker = com.github.benmanes.caffeine.cache.Ticker { clock.get() },
        )
        helper.parkPendingIntro(7L, 1L, PendingIntro(mockk(relaxed = true), null, 60))

        clock.addAndGet(TimeUnit.MINUTES.toNanos(IntroHelper.PENDING_INTRO_TTL_MINUTES - 1))
        assertNotNull(helper.pendingIntro(7L, 1L), "should still be waiting inside the window")

        clock.addAndGet(TimeUnit.MINUTES.toNanos(2))
        assertNull(helper.pendingIntro(7L, 1L), "should have been released once the window passed")
    }

    @Test
    fun `the hold is bounded by Discord's interaction lifetime`() {
        // Past 15 minutes the menu or form can't be answered anyway, so
        // holding the payload longer only pins an attachment.
        assertEquals(15L, IntroHelper.PENDING_INTRO_TTL_MINUTES)
    }
}
