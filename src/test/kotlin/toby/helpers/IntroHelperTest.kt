package toby.helpers

import io.mockk.*
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IMusicFileService
import toby.jpa.service.IUserService
import java.io.InputStream
import java.net.URI
import java.util.*

class IntroHelperTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var userService: IUserService
    private lateinit var musicFileService: IMusicFileService
    private lateinit var configService: IConfigService

    private lateinit var event: SlashCommandInteractionEvent
    private lateinit var userDto: UserDto
    private lateinit var musicDto: MusicDto
    private lateinit var attachment: Attachment
    private lateinit var hook: InteractionHook

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        configService = mockk(relaxed = true)

        introHelper = IntroHelper(userService, musicFileService, configService)

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
            event, userDto, 10, null, "http://valid.url", 70, musicDto
        )

        // Verify that handleUrl was called
        verify {
            spykIntroHelper.handleUrl(
                event, userDto, "TestUser", 10, Optional.of(URI.create("http://valid.url")), 70, musicDto
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


        spykIntroHelper.handleMedia(
            event, userDto, 10, attachment, null, 70, musicDto
        )

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
        introHelper.handleMedia(
            event, userDto, 10, null, "invalid-url", 70, musicDto
        )

        // Verifying that the correct error message is sent
        verify(exactly = 1) {
            hook.sendMessage("Please provide a valid link or attachment").queue(any())
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
        every { attachment.size } returns 500_000

        introHelper.handleAttachment(
            event, userDto, "TestUser", 10, attachment, 70, musicDto
        )

        verify {
            hook.sendMessage("Please keep the file size under 400kb").queue(any())
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

    @Test
    fun `persistMusicUrl should persist a valid URL`() {
        introHelper.persistMusicUrl(
            event, userDto, 10, "filename", "http://valid.url", "TestUser", 70, musicDto
        )

        verify {
            musicFileService.updateMusicFile(any())
        }
    }
}
