package toby.command.commands.music

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.guild
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.requestingUserDto
import toby.command.commands.music.intro.SetIntroCommand
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IMusicFileService
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

internal class SetIntroCommandTest : MusicCommandTest {
    private lateinit var setIntroCommand: SetIntroCommand
    private var userDtoHelper: UserDtoHelper = mockk(relaxed = true)
    private var musicFileService: IMusicFileService = mockk(relaxed = true)
    private var configService: IConfigService = mockk(relaxed = true)
    private lateinit var mentionedUserDto: UserDto

    private var introHelper: IntroHelper = IntroHelper(userDtoHelper, musicFileService, configService)

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        mentionedUserDto = mockk(relaxed = true) {
            every { musicDtos } returns emptyList<MusicDto>().toMutableList()
        }
        configService = mockk()
        setIntroCommand = SetIntroCommand(introHelper)

        every { event.getOption("volume") } returns mockk {
            every { asInt } returns 20
        }
        every { event.getOption("link") } returns mockk {
            every { asString } returns "https://www.youtube.com/"
        }
        every { event.getOption("users")?.mentions } returns mockk {
            every { members } returns emptyList()
        }
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearAllMocks()
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttached_setsIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(event)
        val volumeConfig = ConfigDto("DEFAULT_VOLUME", "20", "1")

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns volumeConfig

        // Act
        setIntroCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(ofType<MusicDto>()) }
        verify {
            event.hook.sendMessage("Successfully set UserName's intro song #1 to 'https://www.youtube.com/' with volume '20'")
        }
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttachedWithExistingMusicFile_createsSecondIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(event)
        val attachmentOptionMapping = mockk<OptionMapping>()


        every { userDtoHelper.calculateUserDto(1L, 1L) } returns requestingUserDto
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { requestingUserDto.musicDtos } returns listOf(
            MusicDto(
                UserDto(1, 1),
                1,
                "filename",
                20,
                null
            )
        ).toMutableList()
        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)

        // Act
        setIntroCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(ofType<MusicDto>()) }
        verify {
            event.hook.sendMessage("Successfully set UserName's intro song #2 to 'https://www.youtube.com/' with volume '20'")
        }
    }

    @Test
    fun testIntroSong_withSuperuser_andMentionedMembers_setsMentionedMembersIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(event)
        val userOptionMapping = mockk<OptionMapping>()

        every { event.getOption("attachment") } returns mockk(relaxed = true)
        every { event.getOption("users") } returns userOptionMapping
        every { userDtoHelper.calculateUserDto(0L, 1L) } returns requestingUserDto
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns mockk(relaxed = true)

        setupMentions(userOptionMapping)

        // Act
        setIntroCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(ofType<MusicDto>()) }
        verify {
            event.hook.sendMessage(
                "Successfully set Another Username's intro song #1 to 'https://www.youtube.com/' with volume '20'"
            )
        }
    }

    @Test
    fun testIntroSong_withoutPermissionsAndSomeoneMentioned_andValidLinkAttached_doesNotSetIntroViaUrl() {
        // Arrange
        val commandContext = CommandContext(event)
        val userOptionMapping = mockk<OptionMapping>()
        val mentions = mockk<Mentions>()

        every { event.getOption("users") } returns userOptionMapping
        every { userOptionMapping.mentions } returns mentions
        every { mentions.members } returns listOf(member)

        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { guild.owner } returns member
        every { member.effectiveName } returns "Effective Name"
        every { requestingUserDto.superUser } returns false

        // Act
        setIntroCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify {
            event.hook.sendMessageFormat(
                eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name")
            )
        }
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withSuperuser_andValidAttachment_setsIntroViaAttachment_andCreatesNewMusicFile() {
        // Arrange
        val commandContext = CommandContext(event)
        val attachmentOptionMapping = mockk<OptionMapping>()

        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { event.getOption("link") } returns mockk {
            every { asString } returns ""
        }

        // Act
        setIntroCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(any()) }
        verify {
            event.hook.sendMessage(
                eq("Successfully set UserName's intro song #1 to 'filename' with volume '20'")
            )
        }
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun testIntroSong_withSuperuser_andMentionedMembers_setsIntroViaAttachment() {
        // Arrange
        val commandContext = CommandContext(event)
        val attachmentOptionMapping = mockk<OptionMapping>()
        val userOptionMapping = mockk<OptionMapping>()

        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)
        every { userDtoHelper.calculateUserDto(0L, 1L) } returns requestingUserDto
        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { event.getOption("link") } returns mockk {
            every { asString } returns ""
        }

        setupMentions(userOptionMapping)

        // Act
        setIntroCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify { musicFileService.createNewMusicFile(ofType<MusicDto>()) }
        verify {
            event.hook.sendMessage(
                "Successfully set Another Username's intro song #1 to 'filename' with volume '20'"
            )
        }
    }

    @Test
    fun testIntroSong_withSuperuser_andValidLinkAttachedWithExistingMusicFiles_doesNotCreateMusicFileWhenAtLimit() {
        // Arrange
        val commandContext = CommandContext(event)
        val attachmentOptionMapping = mockk<OptionMapping>()


        every { configService.getConfigByName("DEFAULT_VOLUME", "1") } returns ConfigDto("DEFAULT_VOLUME", "20", "1")
        every { requestingUserDto.musicDtos } returns listOf(
            MusicDto(
                UserDto(1, 1),
                1,
                "filename1",
                20,
                null
            ),
            MusicDto(
                UserDto(1, 1),
                2,
                "filename2",
                20,
                null
            ),
            MusicDto(
                UserDto(1, 1),
                3,
                "filename3",
                20,
                null
            )
        ).toMutableList()
        every { event.getOption("attachment") } returns attachmentOptionMapping
        setupAttachments(attachmentOptionMapping)

        // Act
        setIntroCommand.handleMusicCommand(
            commandContext,
            MusicCommandTest.playerManager,
            requestingUserDto,
            0
        )

        // Assert
        verify(exactly = 0) { musicFileService.createNewMusicFile(ofType<MusicDto>()) }
        verify {
            event.hook.sendMessage("Select the intro you'd like to replace with your new upload as we only allow 3 intros")
        }
    }

    private fun setupMentions(userOptionMapping: OptionMapping) {
        val mentions = mockk<Mentions>()
        val mentionedMember = mockk<Member>(relaxed = true)
        every { userOptionMapping.mentions } returns mentions
        every { mentions.members } returns listOf(mentionedMember)
        every { mentionedMember.idLong } returns 1L
        every { mentionedMember.effectiveName } returns "Another Username"
    }

    private fun setupAttachments(
        attachmentOptionMapping: OptionMapping,
        fileExt: String = "mp3",
        fileSize: Int = 1000
    ) {
        every { attachmentOptionMapping.asAttachment } returns mockk {
            every { fileExtension } returns fileExt
            every { fileName } returns "filename"
            every { size } returns fileSize
            every { proxy } returns mockk {
                every { download() } returns CompletableFuture.completedFuture(mockk(relaxed = true))
            }
        }

    }
}
