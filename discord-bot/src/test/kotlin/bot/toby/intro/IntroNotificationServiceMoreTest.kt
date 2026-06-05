package bot.toby.intro

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.InputData
import bot.toby.helpers.UserDtoHelper
import database.dto.user.UserDto
import database.service.music.MusicFileService
import database.service.user.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Additional coverage for [IntroNotificationService] public methods not
 * exercised by [IntroNotificationServiceOptOutTest]:
 * - [IntroNotificationService.saveUserMusicDto] — default volume, display name, DM confirmation
 * - [IntroNotificationService.determineMusicBlob] — URL branch, attachment branch (null download)
 * - [IntroNotificationService.determineFileName]  — URL branch, attachment branch
 *
 * No Spring context, DB, or network required; all JDA and service deps
 * are mocked relaxed.
 */
class IntroNotificationServiceMoreTest {

    private val discordId = 100L
    private val guildId = 42L

    private lateinit var user: User
    private lateinit var guild: Guild
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var musicFileService: MusicFileService
    private lateinit var mediaLoader: IntroMediaLoader
    private lateinit var service: IntroNotificationService

    @BeforeEach
    fun setup() {
        user = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        mediaLoader = mockk(relaxed = true)

        every { user.idLong } returns discordId
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Guild"

        val userDto = UserDto(discordId = discordId, guildId = guildId)
        every { userDtoHelper.calculateUserDto(discordId, guildId) } returns userDto

        service = IntroNotificationService(
            userDtoHelper = userDtoHelper,
            musicFileService = musicFileService,
            httpHelper = mockk<HttpHelper>(relaxed = true),
            eventWaiter = mockk<EventWaiter>(relaxed = true),
            validationService = mockk<IntroValidationService>(relaxed = true),
            mediaLoader = mediaLoader,
            notificationPrefService = mockk<UserNotificationPrefService>(relaxed = true),
        )
    }

    // ---- saveUserMusicDto ----

    @Test
    fun `saveUserMusicDto persists a new music file via musicFileService`() {
        val input = InputData.Url("https://youtu.be/abc")

        service.saveUserMusicDto(user, guild, input, volume = 75)

        verify(exactly = 1) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `saveUserMusicDto falls back to volume 90 when no volume provided`() {
        val input = InputData.Url("https://youtu.be/abc")
        val captured = slot<database.dto.music.MusicDto>()
        every { musicFileService.createNewMusicFile(capture(captured)) } returns null

        service.saveUserMusicDto(user, guild, input, volume = null)

        assertEquals(90, captured.captured.introVolume)
    }

    @Test
    fun `saveUserMusicDto uses provided volume when supplied`() {
        val input = InputData.Url("https://youtu.be/abc")
        val captured = slot<database.dto.music.MusicDto>()
        every { musicFileService.createNewMusicFile(capture(captured)) } returns null

        service.saveUserMusicDto(user, guild, input, volume = 50)

        assertEquals(50, captured.captured.introVolume)
    }

    @Test
    fun `saveUserMusicDto uses displayName when provided`() {
        val input = InputData.Url("https://youtu.be/abc")
        val captured = slot<database.dto.music.MusicDto>()
        every { musicFileService.createNewMusicFile(capture(captured)) } returns null

        service.saveUserMusicDto(user, guild, input, volume = null, displayName = "My Song")

        assertEquals("My Song", captured.captured.fileName)
    }

    @Test
    fun `saveUserMusicDto falls back to determinedFileName when displayName is null`() {
        val input = InputData.Url("https://youtu.be/xyz")
        val captured = slot<database.dto.music.MusicDto>()
        every { musicFileService.createNewMusicFile(capture(captured)) } returns null

        service.saveUserMusicDto(user, guild, input, volume = null, displayName = null)

        // For Url, the fileName should equal the URI string.
        assertEquals("https://youtu.be/xyz", captured.captured.fileName)
    }

    @Test
    fun `saveUserMusicDto opens a private channel to confirm after saving`() {
        val input = InputData.Url("https://youtu.be/abc")

        service.saveUserMusicDto(user, guild, input, volume = null)

        // The confirmation DM path opens a private channel.
        verify(exactly = 1) { user.openPrivateChannel() }
    }

    // ---- determineMusicBlob ----

    @Test
    fun `determineMusicBlob returns URI bytes for Url input`() {
        val uri = "https://youtu.be/abc"
        val input = InputData.Url(uri)

        val result = service.determineMusicBlob(input)

        assertEquals(uri.toByteArray().toList(), result?.toList())
    }

    @Test
    fun `determineMusicBlob returns null for Attachment input when download fails`() {
        val attachment = mockk<Attachment>(relaxed = true)
        every { mediaLoader.downloadAttachment(attachment) } returns null
        val input = InputData.Attachment(attachment)

        val result = service.determineMusicBlob(input)

        assertNull(result)
    }

    @Test
    fun `determineMusicBlob returns bytes from mediaLoader for Attachment input when download succeeds`() {
        val attachment = mockk<Attachment>(relaxed = true)
        val fakeStream = java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { mediaLoader.downloadAttachment(attachment) } returns fakeStream
        every { mediaLoader.readContents(fakeStream) } returns byteArrayOf(1, 2, 3)
        val input = InputData.Attachment(attachment)

        val result = service.determineMusicBlob(input)

        assertEquals(listOf<Byte>(1, 2, 3), result?.toList())
    }

    // ---- determineFileName ----

    @Test
    fun `determineFileName returns the URI for Url input`() {
        val uri = "https://youtu.be/abc"
        val input = InputData.Url(uri)

        assertEquals(uri, service.determineFileName(input))
    }

    @Test
    fun `determineFileName returns empty string for blank Url input`() {
        val input = InputData.Url("")

        assertEquals("", service.determineFileName(input))
    }

    @Test
    fun `determineFileName returns the attachment filename for Attachment input`() {
        val attachment = mockk<Attachment>(relaxed = true)
        every { attachment.fileName } returns "my-intro.mp3"
        val input = InputData.Attachment(attachment)

        assertEquals("my-intro.mp3", service.determineFileName(input))
    }

    // ---- promptUserForMusicInfo with null notificationPrefService ----

    @Test
    fun `promptUserForMusicInfo proceeds when notificationPrefService is null (legacy opt-in default)`() {
        // Constructing with null pref service means "no gate" — prompt always fires.
        val serviceWithNullPref = IntroNotificationService(
            userDtoHelper = userDtoHelper,
            musicFileService = musicFileService,
            httpHelper = mockk<HttpHelper>(relaxed = true),
            eventWaiter = mockk<EventWaiter>(relaxed = true),
            validationService = mockk<IntroValidationService>(relaxed = true),
            mediaLoader = mediaLoader,
            notificationPrefService = null,
        )

        serviceWithNullPref.promptUserForMusicInfo(user, guild)

        // Null pref service == gateActive=true, so the prompt is reached.
        verify(exactly = 1) { user.openPrivateChannel() }
    }
}
