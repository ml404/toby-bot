package bot.toby.helpers

import bot.database.dto.MusicDto
import bot.database.service.IConfigService
import bot.toby.handler.EventWaiter
import io.mockk.*
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.URI
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class IntroHelperTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var musicFileService: bot.database.service.IMusicFileService
    private lateinit var configService: IConfigService
    private lateinit var eventWaiter: EventWaiter
    private lateinit var event: SlashCommandInteractionEvent
    private lateinit var userDto: bot.database.dto.UserDto
    private lateinit var musicDto: MusicDto
    private lateinit var attachment: Attachment
    private lateinit var hook: InteractionHook

    @BeforeEach
    fun setup() {
        userDtoHelper = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        eventWaiter = mockk(relaxed = true)

        introHelper = IntroHelper(userDtoHelper, musicFileService, configService, eventWaiter)

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
        introHelper.handleMedia(event, userDto, 10, InputData.Url("invalid"),70, musicDto)

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
    fun `promptUserForMusicInfo should send DM with prompt message`() {
        val user = mockk<User>(relaxed = true)
        val guild = mockk<Guild>(relaxed = true)
        val privateChannel = mockk<PrivateChannel>(relaxed = true)

        // Use a slot to capture the Consumer passed to queue
        val consumerSlot = slot<Consumer<PrivateChannel>>()

        // Mock opening the user's private channel and invoking the queue lambda
        every { user.openPrivateChannel() } returns mockk {
            every { queue(capture(consumerSlot)) } answers {
                consumerSlot.captured.accept(privateChannel) // invoke the consumer
            }
        }

        every { guild.name } returns "TestGuild"

        // Call the method
        introHelper.promptUserForMusicInfo(user, guild)

        // Verify the correct message was sent in the user's DM
        verify {
            privateChannel.sendMessage("You don't have an intro song yet on server 'TestGuild'! Please reply with a YouTube URL or upload a music file, and optionally provide a volume level (1-100). E.g. 'https://www.youtube.com/watch?v=VIDEO_ID_HERE 90'").queue(any())
        }

        // Capture the arguments for waitForMessage to assert them
        val messageWaiterSlot1 = slot<(MessageReceivedEvent) -> Boolean>()
        val messageWaiterSlot2 = slot<(MessageReceivedEvent) -> Unit>()
        val timeoutSlot = slot<Duration>()

        // Adjust the verification for waitForMessage
        verify {
            eventWaiter.waitForMessage(capture(messageWaiterSlot1), capture(messageWaiterSlot2), capture(timeoutSlot), any())
        }

        // Assert the timeout value if necessary
        assert(timeoutSlot.captured == 5.minutes) // Check the timeout value
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

    @Test
    fun `saveUserMusicDto should persist musicDto`() {
        val user = mockk<User>(relaxed = true)
        val guild = mockk<Guild>(relaxed = true)
        val inputData = InputData.Url("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        every { userDtoHelper.calculateUserDto(any(), any()) } returns mockk {
            every { guildId } returns guild.idLong
            every { discordId } returns 1234L
        }
        every { musicFileService.createNewMusicFile(any()) } returns mockk()

        introHelper.saveUserMusicDto(user, guild, inputData, 70)

        verify {
            musicFileService.createNewMusicFile(any())
        }
    }

    @Test
    fun `determineMusicBlob should process URL into byte array`() {
        val input = InputData.Url("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        val result = introHelper.determineMusicBlob(input)

        assert(result != null)
        assert(result!!.isNotEmpty())
    }

    @Test
    fun `determineFileName should return URL as file name for URL input`() {
        val input = InputData.Url("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        val result = introHelper.determineFileName(input)

        assert(result == "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    }

    @Test
    fun `determineFileName should return attachment file name`() {
        val attachment = mockk<Attachment>(relaxed = true)
        every { attachment.fileName } returns "test.mp3"

        val input = InputData.Attachment(attachment)

        val result = introHelper.determineFileName(input)

        assert(result == "test.mp3")
    }

}
